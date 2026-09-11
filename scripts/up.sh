#!/usr/bin/env sh
# То же, что scripts/up.ps1, для рабочего сервера на Linux -- зачем нужны обе
# уборки, расписано там.
set -eu

cd "$(dirname "$0")/.."

stale=$(docker compose ps -aq --status created)
if [ -n "$stale" ]; then
    # shellcheck disable=SC2086  # список id должен разойтись на аргументы
    docker rm -f $stale >/dev/null
fi

docker compose up -d --build "$@"
docker image prune -f --filter label=org.opencontainers.image.title=infra-manager
