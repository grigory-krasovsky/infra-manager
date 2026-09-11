# Поднять локальный стенд, не оставляя мусора в Docker.
#
# То же, что `docker compose up -d --build`, плюс уборка двух видов мусора,
# который иначе копится от запуска к запуску. Хука после сборки у compose нет,
# поэтому уборка живёт здесь, а не в docker-compose.yml.
#
# 1. Недосозданные контейнеры. Прерванный `up` (Ctrl+C, закрытое окно, упавшая
#    обёртка) застаёт compose посреди замены контейнера: прежний уже переименован
#    в <hash>_infra-manager-app-1, новый создан, но не запущен. Следующий запуск
#    спотыкается об занятое имя -- "Conflict. The container name ... is already
#    in use". Состояние `created` бывает только у таких обрубков: намеренно
#    остановленный контейнер имеет `exited`, поэтому фильтр ничего живого не
#    заденет.
#
# 2. Безымянные образы. Каждая пересборка снимает тег с прежнего образа
#    приложения, и тот остаётся как <none> насовсем -- за сутки правок набирается
#    два десятка. Слои у них общие с кэшем сборки, поэтому само удаление образа
#    освобождает немного; но пока образ жив, эти слои не отдаст и кэш. Фильтр по
#    метке обязателен: без него prune снёс бы и безымянные образы других проектов
#    на этой машине. Метка ставится в Dockerfile.
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
    $stale = @(docker compose ps -aq --status created)
    if ($stale.Count -gt 0) {
        # Сообщение по-английски не случайно: Windows PowerShell 5.1 читает .ps1
        # без BOM как ANSI, и кириллица в выводе стала бы кракозябрами.
        Write-Host "Removing $($stale.Count) half-created container(s) left by an interrupted up"
        docker rm -f $stale | Out-Null
    }

    docker compose up -d --build @args
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    docker image prune -f --filter 'label=org.opencontainers.image.title=infra-manager'
} finally {
    Pop-Location
}
