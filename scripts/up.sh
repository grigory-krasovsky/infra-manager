#!/usr/bin/env sh
# То же, что scripts/up.ps1, для рабочего сервера на Linux -- зачем нужна
# уборка, расписано там.
set -eu

cd "$(dirname "$0")/.."

docker compose up -d --build "$@"
docker image prune -f --filter label=org.opencontainers.image.title=infra-manager
