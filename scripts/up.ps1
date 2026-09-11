# Поднять локальный стенд, не оставляя мусора в Docker.
#
# То же, что `docker compose up -d --build`, плюс уборка. Compose на каждой
# пересборке снимает тег с прежнего образа приложения, и тот остаётся как
# <none> насовсем: за сутки правок их набирается два десятка по 350 МБ. Хука
# после сборки у compose нет, поэтому уборка живёт здесь, а не в
# docker-compose.yml.
#
# Фильтр по метке обязателен: без него prune снёс бы и безымянные образы других
# проектов на этой машине. Метка ставится в Dockerfile.
#
# Аргументы уходят в compose как есть:
#   .\scripts\up.ps1                         # обычный запуск
#   .\scripts\up.ps1 --force-recreate app    # после правки .env
#
# $ErrorActionPreference здесь намеренно оставлен по умолчанию: со 'Stop' в
# Windows PowerShell 5.1 любая строка, которую compose пишет в stderr (а прогресс
# сборки он пишет именно туда), при перенаправлении вывода становится
# терминирующей ошибкой. Успех определяется кодом возврата, а не stderr.

Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose up -d --build @args
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    docker image prune -f --filter 'label=org.opencontainers.image.title=infra-manager'
} finally {
    Pop-Location
}
