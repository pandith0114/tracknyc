# Terraform skeleton — **not deployed, not finished**

Nothing here has ever been applied. It is a starting point that shows the shape of a
deployment, not a working module. Treat every file as a sketch to be replaced.

## What a deployment needs

TrackNYC is small: one VM comfortably runs the whole stack for a personal project.

| Piece | Minimum | Notes |
|---|---|---|
| Compute | 1 VM, 2 vCPU / 4 GB | Runs `docker compose`. Ingest is one process; the push API can scale out later. |
| Database | PostgreSQL 16 **with PostGIS** | Managed Postgres works if the provider offers the PostGIS extension — check before committing. |
| Cache / bus | Redis 7 | Needs Pub/Sub; any managed Redis will do. Persistence is not needed. |
| TLS + domain | A record + certificate | HTTP/2 matters: SSE over HTTP/1.1 hits the six-connection-per-origin limit. |
| Egress | Outbound HTTPS to the MTA endpoints and the tile server | No inbound except 80/443. |

## What is deliberately missing

- `main.tf` here declares **no provider**. Pick one (AWS, GCP, Fly.io, Hetzner…) and fill it in;
  the variables and outputs sketch what the rest of the module should expose.
- No state backend. Configure a remote backend before running this anywhere real.
- No secrets handling. `MTA_API_KEY` and `DATABASE_PASSWORD` must come from the provider's
  secret manager, never from `.tfvars` in git.
- No autoscaling, no multi-AZ, no backups. A personal project does not need them; say so rather
  than pretending otherwise.

## Steps left to fill in

1. Choose a provider and add its `required_providers` block and credentials wiring.
2. Replace the placeholder resources with real ones (instance or container service, managed
   Postgres with PostGIS enabled, managed Redis, DNS record, certificate).
3. Decide how the service gets its image: build in CI and push to a registry, or build on the
   host. The CI workflow already builds both images.
4. Wire secrets from the provider's secret store into the container environment.
5. Point Prometheus at the deployed `/actuator/prometheus`, or ship metrics to a hosted backend.
6. Run `terraform plan` and read it carefully before the first apply.
