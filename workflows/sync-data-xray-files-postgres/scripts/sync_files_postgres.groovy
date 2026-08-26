// sync_files_postgres.groovy
//
// Mirrors EVERY file indexed by Data X-Ray into a Postgres schema, entirely from
// inside this Collibra script task over plain JDBC. Collibra's JVM ships
// org.postgresql.Driver (verified 2026-08 with a probe workflow) and
// java.sql.DriverManager passes the script security validator. Designed for an
// ON-PREMISE Collibra + Postgres: Collibra Cloud's egress proxy only allows
// HTTP(S), so a raw Postgres connection from a Collibra *Cloud* instance times
// out — see CLAUDE.md.
//
// Timer-triggered (nightly 03:00), unattended: on misconfiguration or failure it
// logs the reason, records it in <schema>.sync_runs when it can, and ENDS
// CLEANLY rather than throwing (a timer workflow that fails is retried 3× and
// then disabled until redeployment).
//
// Target model — one schema (default "dxr"):
//   sync_runs        one row per run (status, counts, error)
//   classifications  every Data X-Ray classification + the files column it owns
//   metadata_fields  every /metadata-fields entry + the files column it maps to
//   datasources      distinct datasources seen on file rows
//   files            ONE WIDE TABLE, one row per Data X-Ray file (pk file_id):
//                    fixed columns (name, path, size, dates, owner, …) plus
//                    * one column per classification named "{uuid}_{name}"
//                      (lower-snake name, truncated to Postgres's 63-byte limit):
//                        LABEL            boolean  (true/false = applied or not; a file
//                                                   may be true in several — the full
//                                                   list is also in the `labels` jsonb)
//                        ANNOTATOR        integer  (uniquePhrases; NULL = not
//                                                   listed on the row, 0 = listed
//                                                   with no hit evidence)
//                        ANNOTATOR_DOMAIN integer  (sum over its annotators)
//                        EXTRACTOR        text     (extracted value)
//                    * one column per `source: external` metadata field, named
//                      "external_{metaField}", typed from the field's type.
//                      Only external fields ever appear on /api/v1/files rows
//                      (as externalMetadata[]); metadata/dxr fields surface as
//                      the fixed columns, ai fields are not exposed on v1 rows.
//   files_staging    scratch copy of files, rebuilt every run
//
// Sync semantics:
//   – Schema is bootstrapped/evolved idempotently on every run (CREATE … IF NOT
//     EXISTS / ADD COLUMN IF NOT EXISTS). A classification renamed in Data X-Ray
//     gets its column renamed (the uuid prefix is the identity).
//   – The NDJSON stream is batch-inserted into files_staging (1000 rows per
//     JDBC batch/commit), then merged into files with
//     INSERT … ON CONFLICT (file_id) DO UPDATE.
//   – HARD DELETE: after a successful full load, rows whose sync_run_id is not
//     the current run are deleted. A run that streamed 0 rows, or failed, skips
//     the delete (transient-outage guard).
//   – Row-shape assumptions (fileId, labels[], annotators[].uniquePhrases,
//     extractedMetadata[], externalMetadata[]) mirror import_collector.groovy.
//
// Collibra script security validator gotchas (learned here): no reflection
// (Class.forName, getClass) and NO method call named `execute` (it matches
// Groovy's shell-out String.execute()) — use executeUpdate/executeQuery.
import com.fasterxml.jackson.databind.ObjectMapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.sql.DriverManager
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant

// ---------------------------------------------------------------------------
// Helpers (Groovy script methods are hoisted)
// ---------------------------------------------------------------------------

def qi(String ident) { '"' + ident.replace('"', '""') + '"' }

// lower snake_case, ascii only, trimmed underscores
def sanitizeName(Object name) {
    def s = (name ?: '').toString().toLowerCase()
        .replaceAll('[^a-z0-9]+', '_')
        .replaceAll('^_+|_+$', '')
    return s ?: 'unnamed'
}

// Build "{prefix}_{name}" cut to 63 bytes (all ascii after sanitizing, so
// chars == bytes); make unique against `taken`, then reserve it.
def uniqueColumnName(String prefix, Object name, Set taken) {
    def base = (prefix + '_' + sanitizeName(name))
    if (base.length() > 63) { base = base.substring(0, 63).replaceAll('_+$', '') }
    def candidate = base
    int n = 2
    while (taken.contains(candidate)) {
        def suffix = '_' + n
        candidate = base.substring(0, Math.min(base.length(), 63 - suffix.length())).replaceAll('_+$', '') + suffix
        n++
    }
    taken << candidate
    return candidate
}

// Postgres type per classification type, as information_schema reports it.
def pgTypeForClassification(String type) {
    switch (type) {
        case 'LABEL': return 'boolean'
        case 'ANNOTATOR': return 'integer'
        case 'ANNOTATOR_DOMAIN': return 'integer'
        default: return 'text'
    }
}

def pgTypeForMetadataField(String type) {
    switch (type) {
        case 'BOOLEAN': return 'boolean'
        case 'NUMBER': return 'numeric'
        case 'DATE': return 'timestamp with time zone'
        case 'OBJECT': return 'jsonb'
        default: return 'text'
    }
}

def toTimestamp(Object v) {
    if (v == null) { return null }
    if (v instanceof Timestamp) { return v }
    try { return Timestamp.from(Instant.parse(v.toString())) } catch (Exception ignored) { return null }
}

def toLong(Object v) {
    if (v == null) { return null }
    if (v instanceof Number) { return ((Number) v).longValue() }
    try { return Long.parseLong(v.toString()) } catch (Exception ignored) { return null }
}

def toInt(Object v) {
    if (v == null) { return null }
    if (v instanceof Number) { return ((Number) v).intValue() }
    try { return Integer.parseInt(v.toString()) } catch (Exception ignored) { return null }
}

// Bind one value according to the column's information_schema data_type.
def bind(ps, int idx, Object value, String dataType) {
    if (value == null) {
        switch (dataType) {
            case 'boolean': ps.setNull(idx, Types.BOOLEAN); break
            case 'integer': ps.setNull(idx, Types.INTEGER); break
            case 'bigint': ps.setNull(idx, Types.BIGINT); break
            case 'numeric': ps.setNull(idx, Types.NUMERIC); break
            case 'timestamp with time zone': ps.setNull(idx, Types.TIMESTAMP); break
            case 'jsonb': ps.setNull(idx, Types.OTHER); break
            case 'uuid': ps.setNull(idx, Types.OTHER); break
            default: ps.setNull(idx, Types.VARCHAR)
        }
        return
    }
    switch (dataType) {
        case 'boolean':
            ps.setBoolean(idx, value instanceof Boolean ? value : value.toString().equalsIgnoreCase('true'))
            break
        case 'integer':
            def i = toInt(value)
            if (i == null) { ps.setNull(idx, Types.INTEGER) } else { ps.setInt(idx, i) }
            break
        case 'bigint':
            def l = toLong(value)
            if (l == null) { ps.setNull(idx, Types.BIGINT) } else { ps.setLong(idx, l) }
            break
        case 'numeric':
            try { ps.setBigDecimal(idx, new BigDecimal(value.toString())) } catch (Exception ignored) { ps.setNull(idx, Types.NUMERIC) }
            break
        case 'timestamp with time zone':
            def ts = toTimestamp(value)
            if (ts == null) { ps.setNull(idx, Types.TIMESTAMP) } else { ps.setTimestamp(idx, ts) }
            break
        case 'jsonb':
            ps.setObject(idx, value instanceof String ? value : JsonOutput.toJson(value), Types.OTHER)
            break
        case 'uuid':
            ps.setObject(idx, value.toString(), Types.OTHER)
            break
        default:
            ps.setString(idx, value.toString())
    }
}

def httpGetJson(String url, String token) {
    def conn = (HttpURLConnection) new URL(url).openConnection()
    conn.setRequestMethod('GET')
    conn.setRequestProperty('Authorization', "Bearer ${token}")
    conn.setRequestProperty('Accept', 'application/json')
    conn.setConnectTimeout(30_000)
    conn.setReadTimeout(60_000)
    def code = conn.getResponseCode()
    if (code != 200) {
        def errBody = ''
        try { errBody = conn.getErrorStream()?.getText('UTF-8') ?: '' } catch (Exception ignored) { /* best-effort */ }
        throw new RuntimeException("Data X-Ray ${url} returned HTTP ${code}: ${errBody.take(300)}")
    }
    def parsed = new JsonSlurper().parseText(conn.getInputStream().getText('UTF-8'))
    if (!(parsed?.data instanceof List)) {
        throw new RuntimeException("Data X-Ray ${url}: response missing 'data' array")
    }
    return parsed.data
}

// Ordered column_name → data_type for a table.
def columnTypes(conn, String schema, String table) {
    def out = new LinkedHashMap()
    def ps = conn.prepareStatement('SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position')
    ps.setString(1, schema)
    ps.setString(2, table)
    def rs = ps.executeQuery()
    while (rs.next()) { out[rs.getString(1)] = rs.getString(2) }
    rs.close()
    ps.close()
    return out
}

// Run a DDL / non-query statement. (Not named `execute`: the validator bans it.)
def runSql(conn, String statement) {
    def st = conn.createStatement()
    try { st.executeUpdate(statement) } finally { st.close() }
}

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

def isPlaceholder = { String s -> s.startsWith('<paste ') && s.endsWith('>') }
def config = [:]
def missing = []
['dataxrayUrl', 'dataxrayAuthToken', 'pgHost', 'pgPort', 'pgDatabase', 'pgSchema', 'pgUser', 'pgPassword', 'pgSslMode'].each { k ->
    def v = (execution.getVariable(k) ?: '').toString().trim()
    if (v.isEmpty() || isPlaceholder(v)) { missing << k } else { config[k] = v }
}
if (!missing.isEmpty()) {
    loggerApi.warn("Postgres file sync skipped: configuration variables not set: ${missing.join(', ')}. Set them on the workflow settings page.")
    return
}
def dataxrayUrl = config.dataxrayUrl.replaceAll('/+$', '')
def token = config.dataxrayAuthToken
def SCHEMA = config.pgSchema
def jdbcUrl = "jdbc:postgresql://${config.pgHost}:${config.pgPort}/${config.pgDatabase}?sslmode=${config.pgSslMode}&reWriteBatchedInserts=true&connectTimeout=30&socketTimeout=900&ApplicationName=collibra-dxr-sync".toString()
def BATCH_SIZE = 1000
def FETCH_ATTEMPTS = 3

def S = qi(SCHEMA)
def T_FILES = "${S}.${qi('files')}".toString()
def T_STAGE = "${S}.${qi('files_staging')}".toString()
def T_RUNS = "${S}.${qi('sync_runs')}".toString()
def T_CLASS = "${S}.${qi('classifications')}".toString()
def T_META = "${S}.${qi('metadata_fields')}".toString()
def T_DS = "${S}.${qi('datasources')}".toString()

// Fixed metadata/dxr fields that surface as first-class columns on v1 rows.
def FIXED_FIELD_MAP = [
    'metadata:CREATION_DATE': 'created_at', 'metadata:MODIFIED_DATE': 'last_modified_at',
    'metadata:OWNER': 'owner_name', 'metadata:CREATED_BY': 'created_by_name',
    'metadata:MODIFIED_BY': 'modified_by_name', 'metadata:WHO_CAN_ACCESS': 'who_can_access',
    'metadata:geolocation': 'coordinates',
    'dxr:mime_type': 'mime_type', 'dxr:metadata_extraction_status': 'metadata_extraction_status',
]

def startedAt = new Date()
def conn = null
def runId = null
def status = 'failed'
def counts = [seen: 0, inserted: 0, updated: 0, deleted: 0, columnsAdded: 0, skippedNoId: 0, unknownRefs: 0]
def errorText = null

try {
    conn = DriverManager.getConnection(jdbcUrl, config.pgUser, config.pgPassword)
    conn.setAutoCommit(false)

    // --- 1. Bootstrap schema ------------------------------------------------
    runSql(conn, "CREATE SCHEMA IF NOT EXISTS ${S}")
    runSql(conn, """CREATE TABLE IF NOT EXISTS ${T_RUNS} (
        id bigserial PRIMARY KEY, started_at timestamptz NOT NULL, finished_at timestamptz,
        status text NOT NULL, files_seen integer, inserted integer, updated integer, deleted integer,
        columns_added integer, error text)""")
    runSql(conn, """CREATE TABLE IF NOT EXISTS ${T_CLASS} (
        id uuid PRIMARY KEY, name text NOT NULL, type text NOT NULL, subtype text, description text,
        column_name text NOT NULL, dxr_created_at timestamptz, dxr_updated_at timestamptz, last_seen_run bigint)""")
    runSql(conn, """CREATE TABLE IF NOT EXISTS ${T_META} (
        source text NOT NULL, meta_field text NOT NULL, type text, column_name text, last_seen_run bigint,
        PRIMARY KEY (source, meta_field))""")
    runSql(conn, """CREATE TABLE IF NOT EXISTS ${T_DS} (
        id uuid PRIMARY KEY, name text, connector_type text, last_seen_run bigint)""")
    runSql(conn, """CREATE TABLE IF NOT EXISTS ${T_FILES} (
        file_id text PRIMARY KEY,
        datasource_id uuid, datasource_name text, connector_type text,
        file_name text, path text, size bigint, mime_type text,
        created_at timestamptz, last_modified_at timestamptz, content_sha256 text,
        scan_depth text, metadata_extraction_status text,
        owner_name text, owner_realm_account_id text, owner_account_type text,
        created_by_name text, modified_by_name text,
        who_can_access jsonb, dlp_labels jsonb, annotations jsonb, coordinates jsonb, labels jsonb,
        first_seen_at timestamptz NOT NULL DEFAULT now(),
        last_seen_at timestamptz NOT NULL DEFAULT now(),
        sync_run_id bigint)""")
    // Fixed columns added after the first release — idempotent evolution for existing schemas.
    runSql(conn, "ALTER TABLE ${T_FILES} ADD COLUMN IF NOT EXISTS labels jsonb")
    runSql(conn, "CREATE INDEX IF NOT EXISTS files_datasource_name_idx ON ${T_FILES} (datasource_name)")
    runSql(conn, "CREATE INDEX IF NOT EXISTS files_sync_run_id_idx ON ${T_FILES} (sync_run_id)")
    conn.commit()

    def ps = conn.prepareStatement("INSERT INTO ${T_RUNS} (started_at, status) VALUES (?, 'running') RETURNING id")
    ps.setTimestamp(1, new Timestamp(startedAt.time))
    def rs = ps.executeQuery()
    rs.next()
    runId = rs.getLong(1)
    rs.close()
    ps.close()
    conn.commit()
    loggerApi.info("Postgres file sync run ${runId} started (schema ${SCHEMA}, ${dataxrayUrl})")

    // --- 2. Classifications → columns ----------------------------------------
    def existingCols = columnTypes(conn, SCHEMA, 'files')
    def taken = new HashSet(existingCols.keySet())
    def knownColumnByClassId = [:]
    ps = conn.prepareStatement("SELECT id::text, column_name FROM ${T_CLASS}")
    rs = ps.executeQuery()
    while (rs.next()) { knownColumnByClassId[rs.getString(1)] = rs.getString(2) }
    rs.close()
    ps.close()

    def classifications = httpGetJson("${dataxrayUrl}/api/v1/classifications", token)
    def classColumn = [:]      // classification id → column name
    def classType = [:]        // classification id → type
    def upsertClass = conn.prepareStatement("""INSERT INTO ${T_CLASS}
        (id, name, type, subtype, description, column_name, dxr_created_at, dxr_updated_at, last_seen_run)
        VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, type = EXCLUDED.type, subtype = EXCLUDED.subtype,
        description = EXCLUDED.description, column_name = EXCLUDED.column_name,
        dxr_created_at = EXCLUDED.dxr_created_at, dxr_updated_at = EXCLUDED.dxr_updated_at, last_seen_run = EXCLUDED.last_seen_run""")
    classifications.each { c ->
        def id = (c.id ?: '').toString()
        def type = (c.type ?: '').toString()
        if (!id) { return }
        def pgType = pgTypeForClassification(type)
        def current = knownColumnByClassId[id]
        def colName
        if (current && existingCols.containsKey(current)) {
            // Existing column: rename if the Data X-Ray name changed and the new name is free.
            taken.remove(current)
            def desired = uniqueColumnName(id, c.name, taken)
            if (desired != current && !existingCols.containsKey(desired)) {
                runSql(conn, "ALTER TABLE ${T_FILES} RENAME COLUMN ${qi(current)} TO ${qi(desired)}")
                existingCols[desired] = existingCols.remove(current)
                loggerApi.info("Renamed column ${current} → ${desired} (classification renamed in Data X-Ray)")
                colName = desired
            } else {
                if (desired != current) { taken.remove(desired); taken << current }
                colName = current
            }
            if (existingCols[colName] != pgType) {
                loggerApi.warn("Column ${colName} is ${existingCols[colName]} but classification type ${type} expects ${pgType}; leaving as is")
            }
        } else {
            colName = uniqueColumnName(id, c.name, taken)
            runSql(conn, "ALTER TABLE ${T_FILES} ADD COLUMN IF NOT EXISTS ${qi(colName)} ${pgType}")
            existingCols[colName] = pgType
            counts.columnsAdded++
        }
        classColumn[id] = colName
        classType[id] = type
        upsertClass.setString(1, id)
        upsertClass.setString(2, (c.name ?: '').toString())
        upsertClass.setString(3, type)
        upsertClass.setString(4, c.subtype?.toString())
        upsertClass.setString(5, c.description?.toString())
        upsertClass.setString(6, colName)
        bind(upsertClass, 7, c.createdAt, 'timestamp with time zone')
        bind(upsertClass, 8, c.updatedAt, 'timestamp with time zone')
        upsertClass.setLong(9, runId)
        upsertClass.addBatch()
    }
    upsertClass.executeBatch()
    upsertClass.close()

    // --- 3. Metadata fields → columns (external source only) ---------------
    def metaFields = httpGetJson("${dataxrayUrl}/api/v1/metadata-fields", token)
    def externalColumn = [:]   // metaField name → column name
    def upsertMeta = conn.prepareStatement("""INSERT INTO ${T_META} (source, meta_field, type, column_name, last_seen_run)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (source, meta_field) DO UPDATE SET type = EXCLUDED.type,
        column_name = COALESCE(EXCLUDED.column_name, ${T_META}.column_name), last_seen_run = EXCLUDED.last_seen_run""")
    metaFields.each { m ->
        def source = (m.source ?: '').toString()
        def field = (m.metaField ?: '').toString()
        def type = (m.type ?: 'TEXT').toString()
        if (!field) { return }
        def colName = null
        if (source == 'external') {
            colName = uniqueColumnName('external', field, taken)
            if (!existingCols.containsKey(colName)) {
                def pgType = pgTypeForMetadataField(type)
                runSql(conn, "ALTER TABLE ${T_FILES} ADD COLUMN IF NOT EXISTS ${qi(colName)} ${pgType}")
                existingCols[colName] = pgType
                counts.columnsAdded++
            }
            externalColumn[field] = colName
        } else {
            colName = FIXED_FIELD_MAP["${source}:${field}".toString()]
        }
        upsertMeta.setString(1, source)
        upsertMeta.setString(2, field)
        upsertMeta.setString(3, type)
        upsertMeta.setString(4, colName)
        upsertMeta.setLong(5, runId)
        upsertMeta.addBatch()
    }
    upsertMeta.executeBatch()
    upsertMeta.close()
    // Fresh staging table with the final column set.
    runSql(conn, "DROP TABLE IF EXISTS ${T_STAGE}")
    runSql(conn, "CREATE UNLOGGED TABLE ${T_STAGE} (LIKE ${T_FILES} INCLUDING DEFAULTS)")
    conn.commit()
    loggerApi.info("Classifications: ${classifications.size()}, metadata fields: ${metaFields.size()} (${externalColumn.size()} external), new columns: ${counts.columnsAdded}")

    // --- 4. Stream files into staging --------------------------------------
    def cols = columnTypes(conn, SCHEMA, 'files')     // final, ordered column list
    def colNames = cols.keySet().toList()
    def colIndex = [:]
    colNames.eachWithIndex { n, i -> colIndex[n] = i }
    def insertSql = "INSERT INTO ${T_STAGE} (${colNames.collect { qi(it) }.join(', ')}) VALUES (${colNames.collect { '?' }.join(', ')})".toString()
    def labelColumns = classColumn.findAll { k, v -> classType[k] == 'LABEL' }.values().toList()
    def datasources = [:]
    def searchUrl = "${dataxrayUrl}/api/v1/files?q="
    def loaded = false

    for (int attempt = 1; attempt <= FETCH_ATTEMPTS && !loaded; attempt++) {
        runSql(conn, "TRUNCATE ${T_STAGE}")
        conn.commit()
        counts.seen = 0
        counts.skippedNoId = 0
        counts.unknownRefs = 0
        datasources.clear()
        def insert = conn.prepareStatement(insertSql)
        def pending = 0
        try {
            def http = (HttpURLConnection) new URL(searchUrl).openConnection()
            http.setRequestMethod('GET')
            http.setRequestProperty('Authorization', "Bearer ${token}")
            http.setRequestProperty('Accept', 'application/x-ndjson')
            http.setConnectTimeout(30_000)
            http.setReadTimeout(120_000)  // between-bytes: a 2-min silence = stalled stream, retry
            def code = http.getResponseCode()
            if (code != 200) {
                def errBody = ''
                try { errBody = http.getErrorStream()?.getText('UTF-8') ?: '' } catch (Exception ignored) { /* best-effort */ }
                throw new RuntimeException("Data X-Ray /api/v1/files returned HTTP ${code}: ${errBody.take(300)}")
            }
            def input = http.getInputStream()
            try {
                def reader = new ObjectMapper().readerFor(Map).readValues(input)
                while (reader.hasNextValue()) {
                    def row = reader.nextValue()
                    def fileId = (row?.fileId ?: row?.id ?: '').toString()
                    if (!fileId) { counts.skippedNoId++; continue }

                    def values = new ArrayList(Collections.nCopies(colNames.size(), null))
                    def ds = row.datasource instanceof Map ? row.datasource : [:]
                    def owner = row.owner instanceof Map ? row.owner : [:]
                    def now = new Timestamp(new Date().time)
                    values[colIndex.file_id] = fileId
                    values[colIndex.datasource_id] = ds.id?.toString()
                    values[colIndex.datasource_name] = ds.name?.toString()
                    values[colIndex.connector_type] = (ds.connector instanceof Map ? ds.connector.type : null)?.toString()
                    values[colIndex.file_name] = row.fileName?.toString()
                    values[colIndex.path] = (row.path ?: row.filePath)?.toString()
                    values[colIndex.size] = row.size
                    values[colIndex.mime_type] = row.mimeType?.toString()
                    values[colIndex.created_at] = row.createdAt
                    values[colIndex.last_modified_at] = row.lastModifiedAt ?: row.modifiedAt
                    values[colIndex.content_sha256] = row.contentSha256?.toString()
                    values[colIndex.scan_depth] = row.scanDepth?.toString()
                    values[colIndex.metadata_extraction_status] = row.metadataExtractionStatus?.toString()
                    values[colIndex.owner_name] = owner.name?.toString()
                    values[colIndex.owner_realm_account_id] = owner.realmAccountId?.toString()
                    values[colIndex.owner_account_type] = owner.accountType?.toString()
                    values[colIndex.created_by_name] = (row.createdBy instanceof Map ? row.createdBy.name : null)?.toString()
                    values[colIndex.modified_by_name] = (row.modifiedBy instanceof Map ? row.modifiedBy.name : null)?.toString()
                    def wca = row.entitlements instanceof Map ? row.entitlements.whoCanAccess : null
                    values[colIndex.who_can_access] = (wca instanceof Collection && !wca.isEmpty()) ? wca : null
                    values[colIndex.dlp_labels] = (row.dlpLabels instanceof Collection && !row.dlpLabels.isEmpty()) ? row.dlpLabels : null
                    values[colIndex.coordinates] = row.coordinates instanceof Map ? row.coordinates : null
                    values[colIndex.first_seen_at] = now
                    values[colIndex.last_seen_at] = now
                    values[colIndex.sync_run_id] = runId

                    if (ds.id) { datasources[ds.id.toString()] = [ds.name?.toString(), values[colIndex.connector_type]] }

                    // Labels: one boolean column per label (explicit false everywhere, true where
                    // applied — a file can carry several) plus the full list as jsonb.
                    labelColumns.each { values[colIndex[it]] = Boolean.FALSE }
                    def labelList = []
                    (row.labels instanceof Collection ? row.labels : []).each { l ->
                        def col = l instanceof Map ? classColumn[(l.id ?: '').toString()] : null
                        if (col) { values[colIndex[col]] = Boolean.TRUE } else { counts.unknownRefs++ }
                        if (l instanceof Map) { labelList << [id: l.id, name: l.name] }
                    }
                    values[colIndex.labels] = labelList.isEmpty() ? null : labelList
                    // Annotators: uniquePhrases per annotator, summed per domain; phrase evidence kept as jsonb.
                    def evidence = []
                    (row.annotators instanceof Collection ? row.annotators : []).each { a ->
                        if (!(a instanceof Map)) { return }
                        def hits = toInt(a.uniquePhrases)
                        if (hits == null) { hits = (a.annotations instanceof Collection) ? a.annotations.size() : 0 }
                        def col = classColumn[(a.id ?: '').toString()]
                        if (col) { values[colIndex[col]] = hits } else { counts.unknownRefs++ }
                        def domId = (a.domain instanceof Map ? a.domain.id : null)?.toString()
                        def domCol = domId ? classColumn[domId] : null
                        if (domCol) { values[colIndex[domCol]] = ((values[colIndex[domCol]] ?: 0) as int) + hits }
                        if (hits > 0) { evidence << [id: a.id, name: a.name, uniquePhrases: hits, annotations: a.annotations ?: []] }
                    }
                    values[colIndex.annotations] = evidence.isEmpty() ? null : evidence
                    // Extractors: value as text.
                    (row.extractedMetadata instanceof Collection ? row.extractedMetadata : []).each { e ->
                        def col = e instanceof Map ? classColumn[(e.id ?: '').toString()] : null
                        if (col) { values[colIndex[col]] = e.value?.toString() } else { counts.unknownRefs++ }
                    }
                    // External metadata fields.
                    (row.externalMetadata instanceof Collection ? row.externalMetadata : []).each { e ->
                        def col = e instanceof Map ? externalColumn[(e.name ?: '').toString()] : null
                        if (col) { values[colIndex[col]] = e.value } else { counts.unknownRefs++ }
                    }

                    for (int i = 0; i < colNames.size(); i++) { bind(insert, i + 1, values[i], cols[colNames[i]]) }
                    insert.addBatch()
                    counts.seen++
                    if (++pending >= BATCH_SIZE) {
                        insert.executeBatch()
                        conn.commit()
                        pending = 0
                        if (counts.seen % 20000 == 0) { loggerApi.info("Postgres file sync run ${runId}: ${counts.seen} rows staged") }
                    }
                }
                if (pending > 0) { insert.executeBatch(); conn.commit() }
                loaded = true
            } finally {
                try { input.close() } catch (Exception ignored) { /* best-effort */ }
                http.disconnect()
            }
        } catch (Exception fetchEx) {
            try { conn.rollback() } catch (Exception ignored) { /* best-effort */ }
            if (attempt < FETCH_ATTEMPTS) {
                loggerApi.warn("Data X-Ray files fetch attempt ${attempt}/${FETCH_ATTEMPTS} failed (${fetchEx.message}) — retrying")
                sleep(5000)
            } else {
                throw new RuntimeException("Could not stream Data X-Ray files after ${FETCH_ATTEMPTS} attempts: ${fetchEx.message}", fetchEx)
            }
        } finally {
            try { insert.close() } catch (Exception ignored) { /* best-effort */ }
        }
    }
    loggerApi.info("Postgres file sync run ${runId}: staged ${counts.seen} rows (${counts.skippedNoId} without id, ${counts.unknownRefs} refs to unknown classifications/fields)")

    // --- 5. Merge staging → files, hard-delete vanished rows, one transaction
    def upsertCols = colNames.findAll { it != 'file_id' && it != 'first_seen_at' }
    def colList = colNames.collect { qi(it) }.join(', ')
    def mergeSql = """WITH ins AS (
        INSERT INTO ${T_FILES} (${colList})
        SELECT ${colList} FROM ${T_STAGE}
        ON CONFLICT (file_id) DO UPDATE SET ${upsertCols.collect { "${qi(it)} = EXCLUDED.${qi(it)}" }.join(', ')}
        RETURNING (xmax = 0) AS inserted)
        SELECT count(*) FILTER (WHERE inserted), count(*) FILTER (WHERE NOT inserted) FROM ins""".toString()
    def st = conn.createStatement()
    rs = st.executeQuery(mergeSql)
    rs.next()
    counts.inserted = rs.getInt(1)
    counts.updated = rs.getInt(2)
    rs.close()
    st.close()

    if (counts.seen > 0) {
        ps = conn.prepareStatement("DELETE FROM ${T_FILES} WHERE sync_run_id IS DISTINCT FROM ?")
        ps.setLong(1, runId)
        counts.deleted = ps.executeUpdate()
        ps.close()
        status = 'ok'
    } else {
        loggerApi.warn("Postgres file sync run ${runId}: Data X-Ray returned 0 files — skipping deletion of existing rows (transient-outage guard)")
        status = 'empty'
    }

    def upsertDs = conn.prepareStatement("""INSERT INTO ${T_DS} (id, name, connector_type, last_seen_run) VALUES (?::uuid, ?, ?, ?)
        ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, connector_type = EXCLUDED.connector_type, last_seen_run = EXCLUDED.last_seen_run""")
    datasources.each { id, v ->
        upsertDs.setString(1, id)
        upsertDs.setString(2, v[0])
        upsertDs.setString(3, v[1])
        upsertDs.setLong(4, runId)
        upsertDs.addBatch()
    }
    upsertDs.executeBatch()
    upsertDs.close()
    runSql(conn, "TRUNCATE ${T_STAGE}")
    conn.commit()
} catch (Exception e) {
    errorText = (e.message ?: 'unknown error').toString()
    loggerApi.error("Postgres file sync run ${runId ?: '-'} FAILED: ${errorText}")
    try { conn?.rollback() } catch (Exception ignored) { /* best-effort */ }
} finally {
    // Record the outcome (the connection may have been rolled back — fresh statement).
    if (conn != null && runId != null) {
        try {
            def ps = conn.prepareStatement("UPDATE ${T_RUNS} SET finished_at = now(), status = ?, files_seen = ?, inserted = ?, updated = ?, deleted = ?, columns_added = ?, error = ? WHERE id = ?")
            ps.setString(1, status)
            ps.setInt(2, counts.seen)
            ps.setInt(3, counts.inserted)
            ps.setInt(4, counts.updated)
            ps.setInt(5, counts.deleted)
            ps.setInt(6, counts.columnsAdded)
            ps.setString(7, errorText)
            ps.setLong(8, runId)
            ps.executeUpdate()
            ps.close()
            conn.commit()
        } catch (Exception e2) {
            loggerApi.error("Postgres file sync: could not record run outcome: ${e2.message}")
        }
    }
    try { conn?.close() } catch (Exception ignored) { /* best-effort */ }
}
def secs = ((new Date().time - startedAt.time) / 1000) as long
loggerApi.info("Postgres file sync run ${runId ?: '-'} finished: status=${status} seen=${counts.seen} inserted=${counts.inserted} updated=${counts.updated} deleted=${counts.deleted} columnsAdded=${counts.columnsAdded} in ${secs}s")
