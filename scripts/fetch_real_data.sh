#!/usr/bin/env bash
#
# Downloads the REAL static GTFS feeds and records the REAL realtime feeds into the same layout
# as fixtures/, so you can replace the synthetic fixtures with authentic data.
#
#   ./scripts/fetch_real_data.sh                 # static downloads + a 60-minute recording
#   RECORD_MINUTES=5 ./scripts/fetch_real_data.sh
#   SKIP_RECORD=1 ./scripts/fetch_real_data.sh   # static data only
#
# Then run against it:
#   TRACKNYC_FIXTURES_DIR=fixtures-real docker compose -f infra/docker-compose.yml up
#
# Endpoint URLs were taken from the MTA developer portal; verify them at
# https://www.mta.info/developers before relying on this script — the MTA has moved these
# hostnames before. MTA_API_KEY is optional and only sent if set.
set -euo pipefail

OUT="${OUT:-fixtures-real}"
RECORD_MINUTES="${RECORD_MINUTES:-60}"
POLL_SECONDS="${POLL_SECONDS:-30}"
MTA_API_KEY="${MTA_API_KEY:-}"

STATIC_SUBWAY="${STATIC_SUBWAY:-https://rrgtfsfeeds.s3.amazonaws.com/gtfs_subway.zip}"
STATIC_LIRR="${STATIC_LIRR:-https://rrgtfsfeeds.s3.amazonaws.com/gtfslirr.zip}"

RT_BASE="${RT_BASE:-https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds}"
declare -A FEEDS=(
  [subway-1234567]="${RT_BASE}/nyct%2Fgtfs"
  [subway-l]="${RT_BASE}/nyct%2Fgtfs-l"
  [lirr]="${RT_BASE}/lirr%2Fgtfs-lirr"
)

curl_mta() {
  local url="$1" dest="$2"
  if [ -n "$MTA_API_KEY" ]; then
    curl -fsSL -H "x-api-key: ${MTA_API_KEY}" -o "$dest" "$url"
  else
    curl -fsSL -o "$dest" "$url"
  fi
}

echo "==> static GTFS -> ${OUT}/gtfs"
mkdir -p "${OUT}/gtfs"
for pair in "subway ${STATIC_SUBWAY}" "lirr ${STATIC_LIRR}"; do
  set -- $pair
  echo "    ${1}: ${2}"
  curl_mta "$2" "${OUT}/gtfs/${1}.zip"
done
echo "    (the loader reads .zip directly; no need to unpack)"

if [ "${SKIP_RECORD:-0}" = "1" ]; then
  echo "==> skipping the realtime recording (SKIP_RECORD=1)"
  exit 0
fi

SNAPSHOTS=$(( RECORD_MINUTES * 60 / POLL_SECONDS ))
echo "==> recording ${#FEEDS[@]} realtime feeds: ${SNAPSHOTS} snapshots every ${POLL_SECONDS}s (~${RECORD_MINUTES} min)"
START_EPOCH=$(date +%s)

for feed in "${!FEEDS[@]}"; do
  mkdir -p "${OUT}/realtime/${feed}"
  : > "${OUT}/realtime/${feed}/index.csv"
  echo "offset_seconds,file,header_timestamp" >> "${OUT}/realtime/${feed}/index.csv"
done

for (( k=0; k<SNAPSHOTS; k++ )); do
  offset=$(( k * POLL_SECONDS ))
  for feed in "${!FEEDS[@]}"; do
    name=$(printf "%04d.pb" "$k")
    if curl_mta "${FEEDS[$feed]}" "${OUT}/realtime/${feed}/${name}"; then
      # The header timestamp is the second varint field of the FeedHeader; reading it needs a
      # protobuf parser, so record the wall-clock fetch time instead. The loader only uses this
      # column for diagnostics — the authoritative timestamp is inside the payload.
      echo "${offset},${name},$(date +%s)" >> "${OUT}/realtime/${feed}/index.csv"
    else
      echo "    WARN: ${feed} fetch failed at offset ${offset}s" >&2
    fi
  done
  printf "\r    %d/%d snapshots" "$((k + 1))" "$SNAPSHOTS"
  sleep "$POLL_SECONDS"
done
printf "\n"

for feed in "${!FEEDS[@]}"; do
  cat > "${OUT}/realtime/${feed}/manifest.properties" <<EOF
# Recorded from the live MTA feeds by scripts/fetch_real_data.sh
feed_id=${feed}
description=Recorded live MTA GTFS-realtime feed
synthetic=false
recorded_start_epoch=${START_EPOCH}
loop_seconds=$(( SNAPSHOTS * POLL_SECONDS ))
format=gtfs-rt-protobuf
EOF
done

echo "==> done. Run with:"
echo "    TRACKNYC_FIXTURES_DIR=${OUT} docker compose -f infra/docker-compose.yml up"
