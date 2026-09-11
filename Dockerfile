# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM amazoncorretto:17-alpine AS build
WORKDIR /build

# Локальный репозиторий Maven живёт в кэше BuildKit, а не в слое образа. В слое
# он укладывался бы в кэш сборки целиком (~170 МБ) заново на каждую правку
# pom.xml, а через mount он один и переиспользуется между сборками и стадиями.
# sharing=locked -- потому что две параллельные сборки разнесли бы ~/.m2.
#
# Прогрев по одному pom.xml -- дело необязательное: обёртка Maven достаёт часть
# плагинов лениво, поэтому падение здесь не должно валить сборку, всё
# недостающее доберёт `package` ниже.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    chmod +x mvnw && (./mvnw -B -q dependency:go-offline || true)

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -DskipTests package

# ---- runtime --------------------------------------------------------------
FROM amazoncorretto:17-alpine

# По этой метке scripts/up.ps1 находит безымянные образы именно этого проекта.
# `docker compose up --build` снимает тег с предыдущего образа, и тот остаётся
# в системе как <none> навсегда -- по одному на каждую пересборку.
LABEL org.opencontainers.image.title="infra-manager"

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build /build/target/infra-manager-*.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
