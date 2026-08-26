# Acceptance criteria — Data X-Ray → Postgres mirror

Given/When/Then acceptance criteria for **Sync Data X-Ray Files to Postgres (Nightly)**.
Status legend: ✅ verified · 🔹 specified, not yet exercised end-to-end.

## Postgres mirror of all files

Owner: **Sync Data X-Ray Files to Postgres (Nightly)** at 03:00 (on-premise Collibra, plain JDBC).

| ID | Given | When | Then | Status |
|---|---|---|---|---|
| PG-1 | The workflow's Data X-Ray + Postgres variables are set | The nightly runs (or is started via REST) | `dxr.files` holds exactly one row per file returned by `GET /api/v1/files` (row count and per-datasource tallies equal the NDJSON stream); `dxr.sync_runs` records `status = ok` with `files_seen`/`inserted`/`updated`/`deleted` | ✅ (100,490 files, local run) |
| PG-2 | Data X-Ray has N classifications | Any run | `dxr.files` has a column `"{uuid}_{snake name}"` (≤ 63 chars) for each; labels are `boolean`, annotators/domains `integer`, extractors `text`; `dxr.classifications.column_name` names it | ✅ (82 columns) |
| PG-3 | A file's annotator lists `uniquePhrases = k` | It is synced | Its annotator column holds `k`, its domain column the sum over that domain's annotators, and `annotations` (jsonb) holds the matched phrases; an annotator listed with 0 hits stores `0`, one not listed stores `NULL` | ✅ |
| PG-4 | A file disappears from Data X-Ray | The next run completes with > 0 files | Its row is **deleted**; `sync_runs.deleted` counts it | ✅ |
| PG-5 | Data X-Ray returns 0 files or the stream fails 3× | The run finishes | No rows are deleted; `sync_runs.status` is `empty`/`failed` with the error text; the timer schedule survives | ✅ (failed case) |
| PG-6 | A classification is renamed in Data X-Ray | The next run | The existing column is **renamed** (uuid prefix is the identity), no data lost | 🔹 |
| PG-7 | The run is repeated with no Data X-Ray changes | It completes | `inserted = 0`, `updated = files_seen`, `deleted = 0` (idempotent) | ✅ |
| PG-8 | Any variable is unset / still a placeholder | The timer fires | The run is skipped with a logged reason; nothing is thrown | 🔹 |

