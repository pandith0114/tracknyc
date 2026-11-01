# Pushing this repository to GitHub

This repo was built offline: it has full history on `main` and `build/initial`, but no remote.
GitHub was not connected to the session that created it, so nothing was pushed and no pull
request was opened. Here is how to do both.

## 1. Create the repository on GitHub

With the [GitHub CLI](https://cli.github.com) (easiest — it creates the repo and sets the
remote in one step; run it from the repository root):

```bash
gh repo create tracknyc --private --source=. --remote=origin --description \
  "Live map of NYC regional trains: Spring Boot, PostGIS, Redis, React, deck.gl"
```

Or by hand: create an empty repository called `tracknyc` on github.com (no README, no
.gitignore, no licence — this repo already has its own history), then:

```bash
git remote add origin git@github.com:<your-username>/tracknyc.git
```

## 2. Push both branches

```bash
git push -u origin main
git push -u origin build/initial
```

`main` holds only the initial empty commit; all the work is on `build/initial`, which is what
the pull request will show.

## 3. Open the pull request

```bash
gh pr create \
  --base main \
  --head build/initial \
  --title "TrackNYC: live NYC regional train map (milestones 1-4)" \
  --body-file - <<'EOF'
Builds TrackNYC from scratch: a live map of NYC subway and LIRR trains.

**What is here**

- `live-core/` — reusable real-time foundation (feed adapters with retry, backoff and circuit
  breakers; Redis latest-state store and Pub/Sub; SSE push gateway with snapshot + diffs;
  PostGIS and geodesy helpers). No knowledge of trains, so a second app could reuse it.
- `tracknyc-service/` — GTFS static loading into PostGIS with Flyway, GTFS-realtime decoding
  including the MTA NYCT extensions, the position engine, REST + SSE API.
- `web/` — React + TypeScript, MapLibre GL base map, deck.gl train layer animated at 60fps.
- `infra/` — docker compose stack, Grafana dashboard, Prometheus alerts, k6 load test.
- `docs/adr/` — six architecture decision records covering the choices worth arguing about.

**Running it**

`docker compose -f infra/docker-compose.yml up --build`, then http://localhost:8088. It runs
in replay mode by default: recorded fixtures loop at real-time speed, so the map is alive with
no API keys.

**Caveats**

The fixtures in `fixtures/` are **synthetic** — generated in the real wire formats, but with
invented timetables and approximate, hand-entered coordinates. `fixtures/README.md` says
exactly what is invented and `scripts/fetch_real_data.sh` downloads the real thing.
Resume metrics in the README are deliberately left as placeholders; `docs/MEASURING.md`
explains how to measure each one.
EOF
```

Or open it in the browser: after pushing, GitHub shows a "Compare & pull request" button for
`build/initial`.

## 4. CI

`.github/workflows/ci.yml` runs on pushes to `main` and `build/**` and on pull requests into
`main`. On the first push it will run:

- backend unit tests, then the Testcontainers integration tests (PostGIS + Redis) — these
  need a Docker daemon, which GitHub runners have;
- the web typecheck, unit tests, production build and the Playwright smoke test;
- an end-to-end job that boots the backend in replay mode and drives the real UI against it;
- Trivy scans (filesystem and images) with results uploaded to the Security tab;
- Docker image builds for the service and the web app, plus a compose smoke test.

The Trivy SARIF upload needs **Code scanning** enabled: Settings → Code security and analysis →
Code scanning → Set up. Without it that step fails with a permissions error while everything
else still passes.

`.github/dependabot.yml` opens weekly dependency PRs for Gradle, npm, Docker and Actions once
the repository exists on GitHub.
