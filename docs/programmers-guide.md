# Programmer's guide — extractors, annotators and the Data X-Ray API

This guide is for technicians who operate the **Data X-Ray → Postgres mirror**
and need to pull the results of *specific* extractors, annotators or labels —
either out of the mirrored Postgres schema, or straight from Data X-Ray's public
REST API. It explains how the pieces fit together, how to look up the identifier
of a classification, and how to query files by it in both places.

Everything below uses the **v1** API (`/api/v1/…`), which is the stable,
backwards-compatible contract. The same endpoints exist under `/api/vbeta/…`
with an identical shape; use v1 unless you need something that only exists in
vbeta.

## 1. The mental model

Data X-Ray has a **catalog of classifications** and, separately, **file rows**
that carry the results of those classifications.

| Classification `type` | What it is | What a file row carries for it |
|---|---|---|
| `EXTRACTOR` | A metadata extractor (regex, standard or LLM-based) that pulls a *value* out of a document — "Contract Type", "Invoice Number", … | An entry in `extractedMetadata[]` with the extracted `value` and its `type` (`TEXT`, `NUMBER`, `BOOLEAN`). Only present when a value was extracted. |
| `ANNOTATOR` | A pattern detector — "Credit card", "IBAN", "UK NHS number", … | An entry in `annotators[]` with `uniquePhrases` (how many distinct phrases matched) and `annotations[]` (each matched phrase and its character offsets). Only present when the annotator matched something. |
| `ANNOTATOR_DOMAIN` | A grouping of annotators — "Financially Sensitive", "PII", … | Not listed on its own; each annotator entry carries its `domain: {id, name}`. |
| `LABEL` | A label applied to a file, either manually or by a smart/rule-based labeller | An entry in `labels[]` with `id` and `name`. |

Two rules follow from this and drive everything else in the guide:

1. **The UUID is the identity, the name is a display string.** Names can be
   edited in the Data X-Ray UI at any time; the `id` never changes. Two
   classifications of *different* types may even share a name (a label called
   "Credit card" and an annotator called "Credit card"). Look the UUID up once,
   then key your queries and your scripts on it.
2. **Absence means "did not match", not "unknown".** A file row lists only the
   extractors that produced a value, the annotators that found phrases, and the
   labels that are applied. Nothing on the row says "this annotator ran and found
   nothing" — you infer that from the absence.

The Postgres mirror turns this into one wide table: every classification in the
catalog becomes a column on `dxr.files`, named `"{uuid}_{lower_snake_name}"`
(cut to Postgres's 63-character identifier limit), and `dxr.classifications`
tells you which column belongs to which classification. See section 5.

## 2. Authentication

All public API calls need a **Personal Access Token (PAT)** sent as a Bearer
token:

```
Authorization: Bearer <token>
```

- Create one in Data X-Ray under **Settings → Security → Generate Personal
  Access Token**. The token is shown once; store it in a password manager or
  secrets vault.
- The token inherits **the permissions of its user** at the time of each call.
  In particular you only see files in datasources that user may see. If a query
  returns nothing, check the user's datasource permissions before suspecting the
  query.
- **One active token per user.** Generating a new PAT silently revokes the old
  one. If the nightly mirror suddenly fails with HTTP 401, somebody probably
  regenerated the token of the service user — the mirror's *Data X-Ray Auth
  Token* variable and any scripts sharing that user now need the new value. Use a
  **dedicated service account** for the mirror so that people generating their
  own tokens can't break it.
- SAML SSO users cannot create PATs; use a local (non-SSO) service account.

Quick check that a token works:

```bash
export DXR_URL=https://your-dxr-host          # no trailing slash
export DXR_TOKEN='paste-token-here'

curl -sS -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/classifications" | head -c 400
```

You should see `{"status":"ok","data":[…`. A `401` means the token is wrong or
revoked; a `403` means the user lacks permission.

## 3. The three endpoints you need

### 3.1 `GET /api/v1/classifications` — the lookup table

Returns **every** classification in the system in one JSON envelope. There is no
filtering on the server; filter client-side.

```json
{
  "status": "ok",
  "data": [
    {
      "id": "e5f6a7b8-c9d0-4234-9f01-567890123456",
      "name": "Credit card",
      "type": "ANNOTATOR",
      "subtype": "REGEX",
      "description": "Payment card numbers (Visa, Mastercard, Amex, …)",
      "createdAt": "2025-01-15T10:30:00Z",
      "updatedAt": "2025-03-02T08:12:44Z",
      "link": "/resource/e5f6a7b8-c9d0-4234-9f01-567890123456",
      "searchLink": "/resource-search/e5f6a7b8-c9d0-4234-9f01-567890123456"
    },
    {
      "id": "b2c3d4e5-f617-4901-bcde-f23456789012",
      "name": "Contract Type",
      "type": "EXTRACTOR",
      "subtype": "SMART",
      "description": "Kind of contract (NDA, MSA, SOW, …)",
      "createdAt": "2025-02-01T09:00:00Z",
      "updatedAt": "2025-02-01T09:00:00Z",
      "link": "/resource/b2c3d4e5-f617-4901-bcde-f23456789012",
      "searchLink": "/resource-search/b2c3d4e5-f617-4901-bcde-f23456789012"
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `id` | UUID — the stable identity. This is the prefix of the mirror column. |
| `name` | Display name as shown in the Data X-Ray UI. Editable, so don't hard-code it. |
| `type` | `ANNOTATOR`, `ANNOTATOR_DOMAIN`, `LABEL` or `EXTRACTOR`. |
| `subtype` | `REGEX`, `DICTIONARY`, `NAMED_ENTITY`, `STANDARD`, `SMART` or `NONE`. Informational. |
| `link` | Path of the classification's page in the Data X-Ray UI. Prepend the base URL. |
| `searchLink` | Path of a UI search that lists every file carrying this classification. Prepend the base URL. Handy to hand to a non-technical colleague. |

List all extractors, or all annotators, with `jq`:

```bash
# id, name, subtype of every extractor
curl -sS -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/classifications" \
  | jq -r '.data[] | select(.type=="EXTRACTOR") | [.id, .name, .subtype] | @tsv'

# every annotator (its domain is not in this payload; file rows carry it, see 3.3)
curl -sS -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/classifications" \
  | jq -r '.data[] | select(.type=="ANNOTATOR") | [.id, .name, .subtype] | @tsv'

# resolve one name → id (case-insensitive, restricted to a type)
curl -sS -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/classifications" \
  | jq -r '.data[] | select(.type=="EXTRACTOR" and (.name|ascii_downcase)=="contract type") | .id'
```

### 3.2 `GET /api/v1/metadata-fields` — external and built-in fields

Lists the typed metadata *fields* Data X-Ray knows about, as
`{source, metaField, type}` with `source` one of `metadata`, `dxr`, `ai`,
`external`. This is **not** where extractors live (they're classifications,
above). You need it only for `source: external` fields supplied by plugin
connectors, which the mirror turns into `"external_{metaField}"` columns.

```bash
curl -sS -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/metadata-fields" \
  | jq -r '.data[] | select(.source=="external") | [.metaField, .type] | @tsv'
```

### 3.3 `GET /api/v1/files?q=<KQL>` — the file rows

Streams **one JSON object per line** (NDJSON / JSON Lines), one per indexed
file. Important properties of this endpoint:

- **The only parameter is `q`**, a KQL filter. There is no paging, no `limit`,
  no field selection. Without `q` the *entire corpus* streams (on a 100k-file
  instance that is ~100 MB and takes ~10 s). Always filter with `q` when you
  can, and always **stream** the response rather than reading it into memory.
- Text matching is case-insensitive; wildcards `*` and `?` go *inside* the
  quotes; dates are unquoted RFC 3339 or `now-7d`; combine with `AND`, `OR`,
  `NOT` and parentheses. Full syntax: the *KQL syntax guide* and the *File
  metadata fields* reference in your Data X-Ray documentation.
- Filtering on `externalMetadata` happens in memory after the export, so those
  queries are slower than the rest.

A file row, trimmed to the parts this guide cares about:

```json
{
  "fileId": "0KQUMpgBVo-c9i0dUnJ8",
  "fileName": "2024-03 MSA Acme.pdf",
  "path": "Legal/Contracts/2024-03 MSA Acme.pdf",
  "size": 1048576,
  "mimeType": "application/pdf",
  "createdAt": "2024-03-01T09:12:00Z",
  "lastModifiedAt": "2024-03-04T16:40:11Z",
  "scanDepth": "DISCOVERY_AND_CLASSIFICATION",
  "metadataExtractionStatus": "SUCCESS",
  "datasource": { "id": "c3d4e5f6-…", "name": "Legal SharePoint", "connector": { "type": "SHAREPOINT_ONLINE_GRAPH_API" } },
  "labels": [
    { "id": "a1b2c3d4-…", "name": "Confidential" }
  ],
  "extractedMetadata": [
    { "id": "b2c3d4e5-f617-4901-bcde-f23456789012", "name": "Contract Type", "value": "MSA", "type": "TEXT" },
    { "id": "9d1e…", "name": "Contract Value", "value": 250000, "type": "NUMBER" }
  ],
  "annotators": [
    {
      "id": "e5f6a7b8-c9d0-4234-9f01-567890123456",
      "name": "Credit card",
      "domain": { "id": "f6a7b8c9-…", "name": "Financially Sensitive" },
      "uniquePhrases": 2,
      "annotations": [
        { "phrase": "371449635398431", "locations": [ { "start": 1240, "end": 1255 } ] },
        { "phrase": "4111111111111111", "locations": [ { "start": 3308, "end": 3324 }, { "start": 5120, "end": 5136 } ] }
      ]
    }
  ],
  "externalMetadata": [ { "name": "project_id", "value": "ACME-2024", "type": "TEXT" } ],
  "entitlements": { "whoCanAccess": [ … ] },
  "owner": { "name": "Sarah Conner", "email": "sarah@example.com", "accountType": "USER", … }
}
```

Note `fileId`, not `id`, and that there is **no UI link field** on file rows.

## 4. Looking up results directly in Data X-Ray

The pattern is always the same: **resolve the UUID from `/classifications`, then
filter `/files` on it.** Filtering by name also works and is fine for a one-off
in a terminal, but a script should use the id so a rename in the UI doesn't
break it.

Use `curl -G --data-urlencode` so you never have to URL-encode KQL by hand.

### 4.1 Files where an extractor produced a value

```bash
EXTRACTOR_ID=b2c3d4e5-f617-4901-bcde-f23456789012

curl -sS -G -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/files" \
     --data-urlencode "q=extractedMetadata.id:\"$EXTRACTOR_ID\"" \
  | jq -r --arg id "$EXTRACTOR_ID" \
      '[.fileId, .datasource.name, .path, (.extractedMetadata[] | select(.id==$id) | .value)] | @tsv'
```

Variants:

```kql
extractedMetadata.name:"Contract Type"                                   -- by name
extractedMetadata: { id:"b2c3d4e5-…" AND value:"MSA" }                   -- a specific value
extractedMetadata: { name:"Contract Value" AND value > 100000 }           -- numeric comparison
extractedMetadata.name:"Contract Type" AND lastModifiedAt > now-30d      -- combined with file fields
```

> **Gotcha:** the KQL field is `extractedMetadata`, after the *result* array on
> the row. `extractors.name:"…"` matches nothing. Likewise a `value` filter
> without the surrounding `{ … }` matches any extractor's value, not just the
> named one — always scope with braces when you filter on both.

To see which files an extractor *failed* on, filter on the extraction status
instead: `metadataExtractionStatus:"UNSUPPORTED_MIME_TYPE"` etc. A file with
`metadataExtractionStatus:"SUCCESS"` but no entry for your extractor simply had
nothing to extract for it.

### 4.2 Files an annotator matched, with the matched phrases

```bash
ANNOTATOR_ID=e5f6a7b8-c9d0-4234-9f01-567890123456

curl -sS -G -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/files" \
     --data-urlencode "q=annotators.id:\"$ANNOTATOR_ID\" AND annotators.uniquePhrases > 0" \
  | jq -r --arg id "$ANNOTATOR_ID" \
      '. as $f | .annotators[] | select(.id==$id) | .annotations[]
       | [$f.fileId, $f.path, .phrase, (.locations|length)] | @tsv'
```

Variants:

```kql
annotators.name:"Credit card" AND annotators.uniquePhrases > 0       -- by name
annotators: { id:"e5f6a7b8-…" AND uniquePhrases >= 5 }                -- heavy hitters only
annotators: { name:"Credit card" AND annotations.phrase:"3714*" }     -- a specific phrase (wildcard)
annotators.domain.name:"Financially Sensitive"                        -- anything in a domain
annotators.domain.id:"f6a7b8c9-…" AND NOT labels.name:"Reviewed"      -- domain, minus labelled files
```

> **Gotcha:** `annotators.uniquePhrases > 0` on its own is true if *any*
> annotator on the row has hits. To require it for *your* annotator, use the
> brace form: `annotators: { id:"…" AND uniquePhrases > 0 }`.

### 4.3 Files carrying a label

```kql
labels.id:"a1b2c3d4-…"
labels.name:"Confidential" AND datasource.name:"Legal SharePoint"
```

### 4.4 Counting instead of listing

There is no count endpoint; count the lines:

```bash
curl -sS -G -H "Authorization: Bearer $DXR_TOKEN" "$DXR_URL/api/v1/files" \
     --data-urlencode 'q=annotators: { name:"Credit card" AND uniquePhrases > 0 }' | wc -l
```

For anything you'll count repeatedly, the Postgres mirror is the better tool
(next section): it's a `SELECT count(*)`.

## 5. Looking up results in the Postgres mirror

The nightly workflow copies the catalog and every file row into Postgres
(default schema `dxr`). Freshness is "as of the last run" — check
`dxr.sync_runs` first if a number looks stale:

```sql
SELECT id, started_at, finished_at, status, files_seen, inserted, updated, deleted, error
FROM dxr.sync_runs ORDER BY id DESC LIMIT 3;
```

### 5.1 Find the column for a classification

`dxr.classifications` is the mirror of `/api/v1/classifications`, plus the
column it owns on `dxr.files`:

```sql
SELECT id, name, type, subtype, column_name
FROM dxr.classifications
WHERE type IN ('EXTRACTOR', 'ANNOTATOR', 'ANNOTATOR_DOMAIN', 'LABEL')
ORDER BY type, name;
```

```
                  id                  |     name      |   type    | subtype |                          column_name
--------------------------------------+---------------+-----------+---------+----------------------------------------------------------------
 e5f6a7b8-c9d0-4234-9f01-567890123456 | Credit card   | ANNOTATOR | REGEX   | e5f6a7b8-c9d0-4234-9f01-567890123456_credit_card
 b2c3d4e5-f617-4901-bcde-f23456789012 | Contract Type | EXTRACTOR | SMART   | b2c3d4e5-f617-4901-bcde-f23456789012_contract_type
```

**Always resolve the column through this table.** Don't build the name yourself:
long names are truncated at 63 characters, collisions get a numeric suffix, and a
rename in Data X-Ray renames the column on the next run (the UUID prefix is what
stays constant). `column_name` is exactly what `information_schema` reports, so
you can also see it with `\d dxr.files` in `psql`.

Column type and meaning by classification type:

| Type | Column type | Value |
|---|---|---|
| `EXTRACTOR` | `text` | The extracted value (numbers and booleans are stored as text). `NULL` = no value extracted for this file. |
| `ANNOTATOR` | `integer` | `uniquePhrases`. `NULL` = annotator not listed on the row (no match). `0` = listed but with no phrase evidence (rare). |
| `ANNOTATOR_DOMAIN` | `integer` | Sum of `uniquePhrases` over the domain's annotators. `NULL` = none matched. |
| `LABEL` | `boolean` | `true` if applied, `false` otherwise (never `NULL`). The full list is also in the `labels` jsonb column. |

### 5.2 Query by extractor

Two steps in plain SQL — look up the column, then use it (the identifier must be
double-quoted because it starts with a digit and contains hyphens):

```sql
SELECT column_name FROM dxr.classifications WHERE type = 'EXTRACTOR' AND name = 'Contract Type';
-- → b2c3d4e5-f617-4901-bcde-f23456789012_contract_type

SELECT file_id, datasource_name, path,
       "b2c3d4e5-f617-4901-bcde-f23456789012_contract_type" AS contract_type
FROM dxr.files
WHERE "b2c3d4e5-f617-4901-bcde-f23456789012_contract_type" IS NOT NULL;
```

Or in one go from `psql`, letting `format('%I')` quote the column and `\gexec`
run the generated statement:

```sql
SELECT format(
  'SELECT file_id, datasource_name, path, %I AS value FROM dxr.files WHERE %I IS NOT NULL ORDER BY path',
  column_name, column_name)
FROM dxr.classifications
WHERE type = 'EXTRACTOR' AND name = 'Contract Type' \gexec
```

Distribution of an extractor's values:

```sql
SELECT format(
  'SELECT %I AS value, count(*) FROM dxr.files GROUP BY 1 ORDER BY 2 DESC',
  column_name)
FROM dxr.classifications WHERE id = 'b2c3d4e5-f617-4901-bcde-f23456789012' \gexec
```

### 5.3 Query by annotator

```sql
-- files where "Credit card" matched, most hits first
SELECT format(
  'SELECT file_id, datasource_name, path, %I AS unique_phrases FROM dxr.files WHERE %I > 0 ORDER BY %I DESC',
  column_name, column_name, column_name)
FROM dxr.classifications WHERE type = 'ANNOTATOR' AND name = 'Credit card' \gexec

-- how many files per datasource carry anything in the "Financially Sensitive" domain
SELECT format(
  'SELECT datasource_name, count(*) FROM dxr.files WHERE %I > 0 GROUP BY 1 ORDER BY 2 DESC',
  column_name)
FROM dxr.classifications WHERE type = 'ANNOTATOR_DOMAIN' AND name = 'Financially Sensitive' \gexec
```

The **matched phrases** are kept in the `annotations` jsonb column, one element
per annotator with hits, in the same shape as the API's `annotators[]` array.
Unnest it to get one row per phrase:

```sql
SELECT f.file_id, f.path, a->>'name' AS annotator, p->>'phrase' AS phrase,
       jsonb_array_length(p->'locations') AS occurrences
FROM dxr.files f
CROSS JOIN LATERAL jsonb_array_elements(f.annotations) AS a
CROSS JOIN LATERAL jsonb_array_elements(a->'annotations') AS p
WHERE a->>'id' = 'e5f6a7b8-c9d0-4234-9f01-567890123456'
ORDER BY f.path, phrase;
```

The same jsonb also answers "which annotators fired on this file?" without
knowing any column names:

```sql
SELECT a->>'name' AS annotator, (a->>'uniquePhrases')::int AS unique_phrases
FROM dxr.files f, jsonb_array_elements(f.annotations) a
WHERE f.file_id = '0KQUMpgBVo-c9i0dUnJ8';
```

### 5.4 Query by label

```sql
SELECT format('SELECT file_id, path FROM dxr.files WHERE %I', column_name)
FROM dxr.classifications WHERE type = 'LABEL' AND name = 'Confidential' \gexec

-- or via the jsonb list, no column lookup needed
SELECT file_id, path FROM dxr.files
WHERE labels @> '[{"name": "Confidential"}]';
```

### 5.5 Joining classifications together

Because every classification is a column on the same row, cross-classification
questions are a single `WHERE`. For example, contracts of type MSA that also
contain a card number and are not yet labelled *Reviewed*:

```sql
SELECT file_id, path
FROM dxr.files
WHERE "b2c3d4e5-f617-4901-bcde-f23456789012_contract_type" = 'MSA'
  AND "e5f6a7b8-c9d0-4234-9f01-567890123456_credit_card" > 0
  AND NOT "a1b2c3d4-e5f6-4890-abcd-ef1234567890_reviewed";
```

Doing the equivalent against the API means one KQL query with `AND`
(section 4), so both routes work; the mirror is just faster to iterate on and
doesn't need a token.

## 6. Ready-made helper scripts

### 6.1 Bash + curl + jq

Save as `dxr-lookup.sh`; requires `curl` and `jq`.

```bash
#!/usr/bin/env bash
# Usage:
#   dxr-lookup.sh classifications [TYPE]           list catalog (optionally only EXTRACTOR|ANNOTATOR|ANNOTATOR_DOMAIN|LABEL)
#   dxr-lookup.sh extractor "Contract Type"        fileId, datasource, path, value  for every file with a value
#   dxr-lookup.sh annotator "Credit card"          fileId, path, phrase, occurrences for every hit
#   dxr-lookup.sh files '<KQL>'                    raw NDJSON for any KQL query
# Needs DXR_URL and DXR_TOKEN in the environment.
set -euo pipefail
: "${DXR_URL:?set DXR_URL, e.g. https://dxr.example.com}"
: "${DXR_TOKEN:?set DXR_TOKEN to a Data X-Ray personal access token}"
auth=(-H "Authorization: Bearer $DXR_TOKEN")

catalog() { curl -sSf "${auth[@]}" "$DXR_URL/api/v1/classifications"; }

# resolve NAME of TYPE → uuid; fails loudly on 0 or >1 matches
resolve() {
  local type=$1 name=$2 ids
  ids=$(catalog | jq -r --arg t "$type" --arg n "$name" \
        '.data[] | select(.type==$t and (.name|ascii_downcase)==($n|ascii_downcase)) | .id')
  [[ -n "$ids" ]] || { echo "no $type named '$name'" >&2; exit 1; }
  [[ $(wc -l <<<"$ids") -eq 1 ]] || { echo "several ${type}s named '$name': $ids" >&2; exit 1; }
  echo "$ids"
}

files() { curl -sSf -G "${auth[@]}" "$DXR_URL/api/v1/files" --data-urlencode "q=$1"; }

case "${1:-}" in
  classifications)
    catalog | jq -r --arg t "${2:-}" \
      '.data[] | select($t=="" or .type==$t) | [.type, .id, .name, .subtype] | @tsv' | sort ;;
  extractor)
    id=$(resolve EXTRACTOR "$2")
    files "extractedMetadata.id:\"$id\"" \
      | jq -r --arg id "$id" '[.fileId, .datasource.name, .path, (.extractedMetadata[] | select(.id==$id) | .value|tostring)] | @tsv' ;;
  annotator)
    id=$(resolve ANNOTATOR "$2")
    files "annotators: { id:\"$id\" AND uniquePhrases > 0 }" \
      | jq -r --arg id "$id" '. as $f | .annotators[] | select(.id==$id) | .annotations[]
                              | [$f.fileId, $f.path, .phrase, (.locations|length)] | @tsv' ;;
  files)
    files "$2" ;;
  *)
    sed -n '2,7p' "$0"; exit 2 ;;
esac
```

### 6.2 Python

Save as `dxr_lookup.py`; needs Python 3.9+ and `pip install requests`. Streams
the NDJSON so memory stays flat however large the corpus is.

```python
#!/usr/bin/env python3
"""Look up extractor / annotator results through the Data X-Ray v1 API.

    export DXR_URL=https://dxr.example.com DXR_TOKEN=...
    python dxr_lookup.py catalog [EXTRACTOR|ANNOTATOR|ANNOTATOR_DOMAIN|LABEL]
    python dxr_lookup.py extractor "Contract Type"  [out.csv]
    python dxr_lookup.py annotator "Credit card"    [out.csv]
    python dxr_lookup.py files '<KQL>'              [out.ndjson]
"""
import csv, json, os, sys
import requests

BASE = os.environ["DXR_URL"].rstrip("/")
HEADERS = {"Authorization": f"Bearer {os.environ['DXR_TOKEN']}"}


def catalog():
    r = requests.get(f"{BASE}/api/v1/classifications", headers=HEADERS, timeout=60)
    r.raise_for_status()
    return r.json()["data"]


def resolve(type_, name):
    """Name → UUID, restricted to one classification type; refuses ambiguity."""
    hits = [c for c in catalog() if c["type"] == type_ and c["name"].lower() == name.lower()]
    if not hits:
        sys.exit(f"no {type_} named {name!r}")
    if len(hits) > 1:
        sys.exit(f"several {type_}s named {name!r}: {[h['id'] for h in hits]}")
    return hits[0]["id"]


def files(kql=None):
    """Yield one dict per file row, streaming /api/v1/files."""
    params = {"q": kql} if kql else None
    with requests.get(f"{BASE}/api/v1/files", headers=HEADERS, params=params,
                      stream=True, timeout=(30, 300)) as r:
        r.raise_for_status()
        for line in r.iter_lines():
            if line:
                yield json.loads(line)


def write_csv(rows, header, path):
    out = open(path, "w", newline="") if path else sys.stdout
    w = csv.writer(out)
    w.writerow(header)
    n = 0
    for row in rows:
        w.writerow(row)
        n += 1
    if path:
        out.close()
        print(f"{n} rows → {path}", file=sys.stderr)


def main(argv):
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "catalog":
        want = argv[2] if len(argv) > 2 else None
        for c in sorted(catalog(), key=lambda c: (c["type"], c["name"].lower())):
            if not want or c["type"] == want:
                print(f'{c["type"]:<17}{c["id"]}  {c["name"]}  ({c.get("subtype")})')
    elif cmd == "extractor":
        eid = resolve("EXTRACTOR", argv[2])
        rows = ((f["fileId"], f["datasource"]["name"], f.get("path"), e.get("value"), e.get("type"))
                for f in files(f'extractedMetadata.id:"{eid}"')
                for e in f.get("extractedMetadata", []) if e["id"] == eid)
        write_csv(rows, ["fileId", "datasource", "path", "value", "type"], argv[3] if len(argv) > 3 else None)
    elif cmd == "annotator":
        aid = resolve("ANNOTATOR", argv[2])
        rows = ((f["fileId"], f["datasource"]["name"], f.get("path"), a["uniquePhrases"], p["phrase"], len(p["locations"]))
                for f in files(f'annotators: {{ id:"{aid}" AND uniquePhrases > 0 }}')
                for a in f.get("annotators", []) if a["id"] == aid
                for p in a.get("annotations", []))
        write_csv(rows, ["fileId", "datasource", "path", "uniquePhrases", "phrase", "occurrences"],
                  argv[3] if len(argv) > 3 else None)
    elif cmd == "files":
        out = open(argv[3], "w") if len(argv) > 3 else sys.stdout
        for f in files(argv[2]):
            out.write(json.dumps(f) + "\n")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
```

Both scripts deliberately resolve names to UUIDs first and refuse ambiguous
names. If you embed one of these in a scheduled job, store the UUID in the job's
configuration rather than the name.

## 7. Gotchas and troubleshooting

| Symptom | Cause / fix |
|---|---|
| `401` on every call | Token wrong or **revoked by a newer token** for the same user (one active PAT per user). Regenerate and update every consumer, including the mirror's *Data X-Ray Auth Token* variable. |
| `/files` returns nothing, no error | The token's user can't see the datasource(s). Permissions are the user's, not the token's. |
| `extractors.name:"…"` finds nothing | The field on file rows is `extractedMetadata` (results), not `extractors`. |
| A `value` filter matches the wrong extractor | Scope it: `extractedMetadata: { id:"…" AND value:"…" }`. Without braces each clause matches *any* element of the array. |
| `annotators.uniquePhrases > 0` returns files without your annotator's hits | Same array semantics; use `annotators: { id:"…" AND uniquePhrases > 0 }`. |
| Query works in a terminal, fails in a script | URL-encoding. Use `curl -G --data-urlencode "q=…"` or `requests` `params=`, never hand-built query strings. |
| Syntax error on a date | Dates are **unquoted**: `lastModifiedAt > 2024-01-01T00:00:00Z`, `createdAt > now-30d`. |
| Wildcard doesn't match | Wildcards live *inside* the quotes: `fileName:"*.pdf"`. Matching is case-insensitive. |
| Script broke after someone renamed a classification | You keyed on `name`. Key on `id`; the mirror renames its column automatically but a script cannot. |
| Column not found in Postgres | Names are truncated to 63 chars and may carry a `_2` suffix — read `column_name` from `dxr.classifications`, don't construct it. Quote it with double quotes. |
| A classification exists in Data X-Ray but not in `dxr.classifications` | Created after the last run; check `dxr.sync_runs` and wait for tonight or start the workflow by hand (see the deployment guide, step 5). |
| Columns for deleted classifications linger | By design; the mirror never drops columns. `dxr.classifications.last_seen_run` shows the last run that still saw it. |
| `/files` is slow with an `externalMetadata` filter | Those filters run in memory after export. Filter on other fields first, or use the mirror's `external_…` columns. |
| Want a link to the file in the Data X-Ray UI | File rows carry none; don't fabricate one. Classifications do carry `link` and `searchLink` (prepend the base URL); the instance can be configured to emit full URLs via the `xray_api_generate_full_urls` Ansible variable. |

## 8. Reference

### Response envelope

JSON endpoints wrap results as `{"status": "ok", "data": …}`; errors are
`{"status": "error", "error": {"code": "…", "message": "…"}}`. `/api/v1/files`
is the exception: it streams bare objects, one per line, with no envelope.

### Classification `type` / `subtype`

| `type` | Typical `subtype` |
|---|---|
| `EXTRACTOR` | `REGEX`, `STANDARD`, `SMART` (LLM) |
| `ANNOTATOR` | `REGEX`, `DICTIONARY`, `NAMED_ENTITY`, `STANDARD` |
| `ANNOTATOR_DOMAIN` | `NONE` |
| `LABEL` | `SMART`, `NONE` |

### KQL fields used in this guide

| Field | Notes |
|---|---|
| `extractedMetadata.id` / `.name` / `.value` / `.type` | Extractor results. `type` is `TEXT`, `NUMBER` or `BOOLEAN`. |
| `annotators.id` / `.name` / `.uniquePhrases` | Annotator results. |
| `annotators.domain.id` / `.domain.name` | The annotator's domain. |
| `annotators.annotations.phrase` | A matched phrase. |
| `labels.id` / `.name` | Applied labels. |
| `externalMetadata.name` / `.value` / `.type` | Plugin-connector fields. |
| `metadataExtractionStatus` | `SUCCESS`, `DISABLED`, `FILTERED`, `TEXT_UNAVAILABLE`, `UNSUPPORTED_MIME_TYPE`, … |
| `datasource.id` / `.name` / `.connector.type`, `fileName`, `path`, `size`, `mimeType`, `createdAt`, `lastModifiedAt`, `owner.*`, `entitlements.whoCanAccess.*` | File-level fields to combine with the above. |

### Where the mirror puts each API field

| API (file row) | `dxr.files` column |
|---|---|
| `fileId` | `file_id` (primary key) |
| `datasource.id` / `.name` / `.connector.type` | `datasource_id`, `datasource_name`, `connector_type` |
| `fileName`, `path`, `size`, `mimeType`, `contentSha256`, `scanDepth`, `metadataExtractionStatus` | same names in snake_case |
| `createdAt`, `lastModifiedAt` | `created_at`, `last_modified_at` |
| `owner.name` / `.realmAccountId` / `.accountType`, `createdBy.name`, `modifiedBy.name` | `owner_name`, `owner_realm_account_id`, `owner_account_type`, `created_by_name`, `modified_by_name` |
| `entitlements.whoCanAccess`, `dlpLabels`, `coordinates` | `who_can_access`, `dlp_labels`, `coordinates` (jsonb) |
| `labels[]` | one `boolean` column per label **and** `labels` (jsonb) |
| `annotators[]` | one `integer` column per annotator and per domain **and** `annotations` (jsonb, hits only) |
| `extractedMetadata[]` | one `text` column per extractor |
| `externalMetadata[]` | one `external_{name}` column per field |
| — | `first_seen_at`, `last_seen_at`, `sync_run_id` (bookkeeping) |

### Further reading

- *Authentication*, *KQL syntax guide* and *File metadata fields* in the Data
  X-Ray documentation (API reference section), and the interactive v1 API
  reference generated from the OpenAPI spec.
- [`deployment-guide.md`](deployment-guide.md) — installing and configuring the
  mirror workflow.
- [`acceptance-criteria.md`](acceptance-criteria.md) — the exact semantics the
  mirror guarantees (NULL vs 0, deletes, renames).
