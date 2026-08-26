# collibra-dxr-postgres-mirror

A single [Collibra](https://www.collibra.com/) workflow, **Sync Data X-Ray Files to Postgres (Nightly)**, that mirrors every file indexed by **Data X-Ray** into one wide Postgres table (a column per classification) every night over plain JDBC. **On-premise Collibra only** — Collibra Cloud's egress proxy allows HTTP(S) only, so the Postgres connection cannot be opened from a Cloud instance.

It is independent of the [Data X-Ray ↔ Collibra workflow bundle](https://github.com/Ohalo-Ltd/collibra-dxr-workflows): nothing here reads or writes Collibra assets, and no shared setup workflow is required.

- **Deploying?** Take the ZIP from the latest release and follow [`docs/deployment-guide.md`](docs/deployment-guide.md) — all through the Collibra UI.
- **Changing it?** See [Developing](#developing).

## Releasing

`gh release create v0.2.0 --generate-notes` in this repo. The [release Action](.github/workflows/release-bundle.yml) checks out the [collibra-workflower](https://github.com/Ohalo-Ltd/collibra-workflower) harness at the version pinned in [`pack.json`](pack.json) (`harnessRef`), runs `deploy.py bundle`, and attaches **`collibra-dxr-postgres-mirror-<tag>.zip`** containing:

```
sync-data-xray-files-postgres.zip
deployment-guide.md
acceptance-criteria.md
images/postgres-sync-variables.png
```

The Action needs the `HARNESS_READ_TOKEN` repository secret (read access to the internal harness repo). Run it from the **Actions** tab (`workflow_dispatch`) to get the bundle as a run artifact without cutting a release.

## Developing

This repo is a *workflow pack* — sources and docs only. Build/deploy tooling, Groovy IDE stubs and Collibra reference docs live in the harness, which mounts this repo as a git submodule at `packs/dxr-postgres-mirror`:

```bash
git clone --recurse-submodules git@github.com:Ohalo-Ltd/collibra-workflower.git
cd collibra-workflower
pip install -r requirements.txt && cp .env.example .env

python deploy.py sync-data-xray-files-postgres --dry-run
python deploy.py sync-data-xray-files-postgres --enable        # deploy to your dev instance
python deploy.py bundle --pack packs/dxr-postgres-mirror
```

To exercise the script against a real Postgres without Collibra, use the harness's `tools/run_script_locally.groovy` (config variables come from `WF_<name>` env vars). Commit and push from inside `packs/dxr-postgres-mirror`, then bump the submodule pointer in the harness. Data X-Ray and Collibra specifics for this workflow are in [CLAUDE.md](CLAUDE.md).
