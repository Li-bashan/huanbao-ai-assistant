#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -eq 9 ]] || die "usage: $0 RELEASE_ID WEB_ROOT NGINX_CONTAINER DATAQUERY_SERVICE DATAQUERY_JAR_PATH GATEWAY_CONTAINER GATEWAY_JAR_PATH SSH_TARGET SSH_ARGS"

RELEASE_ID="$1"
WEB_ROOT="$2"
NGINX_CONTAINER="$3"
DATAQUERY_SERVICE="$4"
DATAQUERY_JAR_PATH="$5"
GATEWAY_CONTAINER="$6"
GATEWAY_JAR_PATH="$7"
SSH_TARGET="$8"
SSH_ARGS="$9"
REMOTE_STAGE="$WEB_ROOT/.release-stage-$RELEASE_ID"

[[ "$RELEASE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] || die "unsafe release id"
[[ "$WEB_ROOT" == /opt/* && "$WEB_ROOT" != */ ]] || die "unsafe web root"
[[ "$DATAQUERY_JAR_PATH" == /* && "$DATAQUERY_JAR_PATH" != *..* ]] || die "unsafe dataquery jar path"
[[ "$GATEWAY_JAR_PATH" == /* && "$GATEWAY_JAR_PATH" != *..* ]] || die "unsafe gateway jar path"

REMOTE_PAYLOAD="$(mktemp)"
trap 'rm -f -- "$REMOTE_PAYLOAD"' EXIT

cat > "$REMOTE_PAYLOAD" <<'REMOTE_SCRIPT'
#!/usr/bin/env bash
set -Eeuo pipefail

die() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ $# -eq 7 ]] || die "remote deployment arguments are incomplete"

RELEASE_ID="$1"
WEB_ROOT="$2"
NGINX_CONTAINER="$3"
DATAQUERY_SERVICE="$4"
DATAQUERY_JAR_PATH="$5"
GATEWAY_CONTAINER="$6"
GATEWAY_JAR_PATH="$7"
REMOTE_STAGE="$WEB_ROOT/.release-stage-$RELEASE_ID"
DATAQUERY_UPLOAD="/tmp/huanbao-dataquery-$RELEASE_ID.jar"
GATEWAY_UPLOAD="/tmp/ai-gateway-$RELEASE_ID.jar"
BACKUP_ROOT="$WEB_ROOT/backups"
BACKUP_DIR="$BACKUP_ROOT/$RELEASE_ID"
DIST_BACKUP="$BACKUP_ROOT/dist-$RELEASE_ID.tar.gz"
ROLLBACK_DIR=""

cleanup() {
  rm -rf -- "$REMOTE_STAGE" "$DATAQUERY_UPLOAD" "$GATEWAY_UPLOAD"
  if [[ -n "$ROLLBACK_DIR" ]]; then
    rm -rf -- "$ROLLBACK_DIR"
  fi
}
trap cleanup EXIT

[[ "$RELEASE_ID" =~ ^[a-zA-Z0-9._-]+$ ]] || die "unsafe release id"
[[ "$WEB_ROOT" == /opt/* && "$WEB_ROOT" != */ ]] || die "unsafe web root"
[[ "$DATAQUERY_JAR_PATH" == /* && "$DATAQUERY_JAR_PATH" != *..* ]] || die "unsafe dataquery jar path"
[[ "$GATEWAY_JAR_PATH" == /* && "$GATEWAY_JAR_PATH" != *..* ]] || die "unsafe gateway jar path"
[[ -d "$WEB_ROOT" && "$WEB_ROOT" != / ]] || die "web root does not exist"
[[ -d "$REMOTE_STAGE/dist" && -f "$REMOTE_STAGE/dist/index.html" ]] || die "staged frontend is incomplete"
[[ -s "$DATAQUERY_UPLOAD" ]] || die "dataquery upload is missing"
[[ -s "$GATEWAY_UPLOAD" ]] || die "gateway upload is missing"
command -v systemctl >/dev/null || die "systemctl is not available"
command -v curl >/dev/null || die "curl is not available"
systemctl cat "$DATAQUERY_SERVICE" >/dev/null 2>&1 || die "dataquery service not found: $DATAQUERY_SERVICE"
docker inspect "$GATEWAY_CONTAINER" >/dev/null 2>&1 || die "gateway container not found: $GATEWAY_CONTAINER"
[[ -f "$DATAQUERY_JAR_PATH" ]] || die "dataquery jar target not found: $DATAQUERY_JAR_PATH"
[[ -f "$GATEWAY_JAR_PATH" ]] || die "gateway jar target not found: $GATEWAY_JAR_PATH"
[[ -d "$(dirname -- "$DATAQUERY_JAR_PATH")" ]] || die "dataquery jar directory not found"
[[ -d "$(dirname -- "$GATEWAY_JAR_PATH")" ]] || die "gateway jar directory not found"

if command -v docker >/dev/null; then
  docker exec "$NGINX_CONTAINER" nginx -t >/dev/null
else
  die "docker is not available for nginx validation"
fi

mkdir -p -- "$BACKUP_ROOT"
[[ ! -e "$BACKUP_DIR" && ! -e "$DIST_BACKUP" ]] || die "release backup already exists: $RELEASE_ID"
mkdir -p -- "$BACKUP_DIR"
tar -czf "$DIST_BACKUP" -C "$WEB_ROOT" dist
cp -a -- "$DATAQUERY_JAR_PATH" "$BACKUP_DIR/$(basename -- "$DATAQUERY_JAR_PATH")"
cp -a -- "$GATEWAY_JAR_PATH" "$BACKUP_DIR/$(basename -- "$GATEWAY_JAR_PATH")"

rollback() {
  echo "Release failed; restoring $RELEASE_ID" >&2
  local status=0
  ROLLBACK_DIR="$(mktemp -d /tmp/huanbao-rollback.XXXXXX)"
  if ! tar -xzf "$DIST_BACKUP" -C "$ROLLBACK_DIR"; then status=1; fi
  if ! rm -rf -- "$WEB_ROOT/dist" || ! cp -a -- "$ROLLBACK_DIR/dist" "$WEB_ROOT/dist"; then status=1; fi
  if ! install -m 0644 "$BACKUP_DIR/$(basename -- "$DATAQUERY_JAR_PATH")" "$DATAQUERY_JAR_PATH"; then status=1; fi
  if ! install -m 0644 "$BACKUP_DIR/$(basename -- "$GATEWAY_JAR_PATH")" "$GATEWAY_JAR_PATH"; then status=1; fi
  if ! systemctl restart "$DATAQUERY_SERVICE"; then status=1; fi
  if ! docker restart "$GATEWAY_CONTAINER" >/dev/null; then status=1; fi
  if ! docker exec "$NGINX_CONTAINER" nginx -t; then status=1; fi
  if ! docker exec "$NGINX_CONTAINER" nginx -s reload; then status=1; fi
  return "$status"
}

apply_release() {
  install -m 0644 "$DATAQUERY_UPLOAD" "$DATAQUERY_JAR_PATH"
  install -m 0644 "$GATEWAY_UPLOAD" "$GATEWAY_JAR_PATH"
  rm -rf -- "$WEB_ROOT/dist"
  cp -a -- "$REMOTE_STAGE/dist" "$WEB_ROOT/dist"
  systemctl restart "$DATAQUERY_SERVICE"
  docker restart "$GATEWAY_CONTAINER" >/dev/null
  docker exec "$NGINX_CONTAINER" nginx -t
  docker exec "$NGINX_CONTAINER" nginx -s reload
}

wait_http() {
  local url="$1"
  local attempt
  for attempt in $(seq 1 30); do
    if curl -fsS --max-time 5 "$url" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

if ! apply_release; then
  if rollback; then
    die "release commands failed; previous release restored"
  else
    die "release commands failed and rollback also failed; inspect $BACKUP_DIR"
  fi
fi

if ! wait_http "http://127.0.0.1:8089/actuator/health" || \
   ! wait_http "http://127.0.0.1:8088/actuator/health" || \
   ! wait_http "http://127.0.0.1:9002/api/ai/health"; then
  if rollback; then
    die "post-release health check failed; previous release restored"
  else
    die "post-release health check failed and rollback also failed; inspect $BACKUP_DIR"
  fi
fi

echo "Release completed: $RELEASE_ID"
echo "Frontend backup: $DIST_BACKUP"
echo "Backend backup: $BACKUP_DIR"
REMOTE_SCRIPT

cat "$REMOTE_PAYLOAD" | ssh $SSH_ARGS "$SSH_TARGET" bash -s -- \
  "$RELEASE_ID" "$WEB_ROOT" "$NGINX_CONTAINER" "$DATAQUERY_SERVICE" "$DATAQUERY_JAR_PATH" \
  "$GATEWAY_CONTAINER" "$GATEWAY_JAR_PATH"
