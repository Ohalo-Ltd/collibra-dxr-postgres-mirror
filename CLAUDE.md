# collibra-dxr-postgres-mirror

A one-workflow *pack* (`sync-data-xray-files-postgres`) built and deployed with the [collibra-workflower](https://github.com/Ohalo-Ltd/collibra-workflower) harness — see that repo's CLAUDE.md for packaging format, configuration-variable rules, redeploy semantics and Groovy runtime facts. This file holds only what is specific to this workflow.

Layout: `pack.json` (bundle name, pinned `harnessRef`, workflow list), `workflows/sync-data-xray-files-postgres/`, `docs/` (deployment guide, acceptance criteria, screenshot — all shipped in the bundle), `workflow-registry.json` (dev-instance definition UUID). Develop from the harness checkout (`packs/dxr-postgres-mirror`); release with `gh release create` here.

**Deliberately decoupled from the Data X-Ray ↔ Collibra bundle** (`collibra-dxr-workflows`): this workflow touches no Collibra assets and is not registered in that bundle's `configure-data-xray-workflows` start-role stamping. Start roles are set by the admin in the UI (deployment guide, Step 4); a fresh import is unrestricted, which is acceptable because the workflow is timer-started.

## Data X-Ray API facts (verified against demo.dataxray.io, 2026-08)

- `/api/v1/files` NDJSON row: `fileId` (not `id`), `fileName`, `path`, `size`, `lastModifiedAt`, `datasource: {id, name}`, `labels: [{id, name}]`, `extractedMetadata: [{id, name, value, type}]`, `annotators: [{id, name, uniquePhrases, annotations: [...]}]`. **No UI link field** on file rows — don't fabricate deep links.
- Extractor results are queried via **`extractedMetadata.name:"X"`** — `extractors.name:"X"` matches nothing.
- File rows list annotators only with hit evidence fields (`uniquePhrases`/`annotations`); labels/extractedMetadata by presence.
- `/api/v1/files` takes **only `q`** (KQL applied post-serialisation over the row JSON) — no limit/fields/pagination; the whole corpus streams (100k rows / 96 MB in ~10 s on demo). Full row DTO: `repo_dxr/dxr/api-specs/v1/files/model.yaml`.
- `/api/v1/metadata-fields` → `{source: metadata|dxr|ai|external, metaField, type}`, **no id**. Only `source: external` fields ever appear on a file row (as `externalMetadata[{name,value,type}]`); `metadata`/`dxr` fields surface as first-class row columns, `ai` isn't exposed on v1 rows at all.
- `/api/v1/classifications` `type ∈ ANNOTATOR | ANNOTATOR_DOMAIN | LABEL | EXTRACTOR`, `subtype ∈ REGEX | DICTIONARY | NAMED_ENTITY | STANDARD | SMART | NONE`.

## Collibra Cloud script sandbox + egress (verified 2026-08 with probe workflows)

- Deploy runs a **script security validator** (`wfDeploySecurityValidationErrorMessage`). Banned: reflection (`Class.forName`, `getClass()`, `.class`) and **any method call named `execute`** (matches Groovy's shell-out `String.execute()`) — `st.execute(sql)` fails, `executeQuery/executeUpdate/executeBatch` pass. `java.sql.DriverManager` itself is allowed.
- `org.postgresql.Driver` **is** on Collibra's classpath, but egress is **HTTP(S)-only**: raw TCP to any host/port (tested 5432/443/8080 against portquiz.net and Cloud SQL) hangs → "The connection attempt failed / Read timed out". Only `HttpURLConnection` to https works. Egress IP observed: `3.248.186.72`.
- To bisect validator failures cheaply: deploy chunks of the script as a throwaway one-task workflow and delete it afterwards (`CollibraClient.delete_workflow`).

## The workflow (`sync_files_postgres.groovy`)

Nightly (03:00) Collibra workflow that mirrors **every** DXR file into one Postgres schema over **plain JDBC** (`java.sql.DriverManager` + Collibra's bundled `org.postgresql.Driver`). **On-premise Collibra only** — because of the egress rule above it cannot run on Collibra Cloud (ohalo.collibra.com validates/deploys it fine but any run would fail to connect). The requirement is an entirely on-premise deliverable; no cloud services are involved. Config vars: `dataxrayUrl`, `dataxrayAuthToken`, `pgHost`, `pgPort` (5432), `pgDatabase` (dxr), `pgSchema` (dxr), `pgUser`, `pgPassword`, `pgSslMode` (require). Start roles: set manually in the UI (see docs/deployment-guide.md Step 4).

Script `sync_files_postgres.groovy` owns the schema (idempotent DDL each run): `sync_runs`, `classifications`, `metadata_fields`, `datasources`, wide `files` (pk `file_id`) + `files_staging` (UNLOGGED, rebuilt per run). Dynamic columns on `files`: one per classification named `"{uuid}_{lower_snake_name}"` truncated to 63 chars (`uniqueColumnName`; renamed in DXR → `RENAME COLUMN`, uuid is the identity): LABEL `boolean` (explicit false), ANNOTATOR/ANNOTATOR_DOMAIN `integer` (uniquePhrases / domain sum; NULL = not listed, 0 = listed without hits), EXTRACTOR `text`; plus `"external_{metaField}"` for `source: external` metadata fields. Load = NDJSON stream → JDBC batches of 1000 (`reWriteBatchedInserts=true`, commit per batch) into staging → `INSERT … ON CONFLICT (file_id) DO UPDATE` (inserted/updated via `xmax = 0`) → **hard delete** `WHERE sync_run_id IS DISTINCT FROM run` — **skipped when 0 rows streamed**. Never throws (nightly convention); outcome in `sync_runs`.

**Local testing**: the harness's `tools/run_script_locally.groovy` runs a workflow script under plain Groovy (`brew install groovy`) with `execution`/`loggerApi` stubbed and pgjdbc + Jackson `@Grab`bed; config vars come from `WF_<name>` env vars. Verified 2026-08-26 against demo DXR (100,490 files, 82 classification columns, ~90 s per full run; rerun idempotent; planted row hard-deleted). The Collibra Cloud deploy is still useful as the **validator/compile check**.

