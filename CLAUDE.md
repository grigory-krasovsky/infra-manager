# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Язык

- **Общение с пользователем — на русском.** Отвечай по-русски, включая объяснения, планы и вопросы.
- **Комментарии в коде и Javadoc — на русском.**
- **Commit-сообщения — на английском.**

Идентификаторы, имена классов/методов, строки логов и содержимое `application.yaml` остаются английскими — по-русски пишется только прозаический текст комментариев.

## Commands

The Maven wrapper is the supported entry point (`.\mvnw.cmd` in PowerShell, `./mvnw` in bash). A system `mvn` also exists on PATH (3.9.11) but the wrapper pins 3.9.16.

```powershell
.\mvnw.cmd test                                             # run all tests
.\mvnw.cmd test "-Dtest=InfraManagerApplicationTests"        # single test class
.\mvnw.cmd test "-Dtest=InfraManagerApplicationTests#contextLoads"   # single test method
.\mvnw.cmd package                                           # build executable jar -> target/infra-manager-0.0.1-SNAPSHOT.jar
.\mvnw.cmd spring-boot:run                                   # run the app
```

Quote `-D` arguments in PowerShell so `#` and other tokens survive parsing.

`.\mvnw.cmd -o` (offline) does **not** work from a clean state — `spring-boot-maven-plugin` is resolved lazily and is not in the local repo until an online build has run.

Running the tests requires a working Docker engine — every integration test starts a `postgres:17-alpine` container through Testcontainers.

```powershell
.\scripts\up.ps1                        # run the service and its database
.\scripts\up.ps1 --force-recreate app   # arguments are passed through to compose
docker compose logs -f app
```

`scripts/up.ps1` (and `scripts/up.sh` for the Linux server) is `docker compose up -d
--build` wrapped in two cleanups, and is what you should run instead of calling compose
directly. Before `up` it removes this project's containers in state `created` — an
interrupted `up` leaves a half-created one holding the service name, and the next run
dies on `Conflict. The container name ... is already in use`. After `up` it runs
`docker image prune` filtered on this project's `org.opencontainers.image.title` label,
because every rebuild untags the previous `infra-manager-app` image and nothing ever
reclaims those `<none>` images on their own. The label filter is what keeps the prune
from touching other projects on the same machine. `docs/runbook.md` has the details.

## Architecture

A Spring MVC service that mirrors pull requests onto a Trello board and announces deployments in Telegram. Everything lives under `com.example.inframanager`.

**Two journals and two workers.** Nothing calls an external API from a request thread. Webhook controllers authenticate, write the raw body to `inbound_event`, and return 200; `InboundEventWorker` interprets it later and queues work in `outbound_task`; `OutboundTaskWorker` performs the Trello and Telegram calls. This exists because webhook redelivery budgets are finite, both target APIs are rate limited, and work in flight has to survive a container restart. Each row is claimed with `SELECT ... FOR UPDATE SKIP LOCKED` in its own transaction, so a crash rolls back to PENDING and the item is simply retried.

Idempotency is structural, not defensive: `inbound_event` is unique on `(source, external_id)` and `outbound_task` on `dedup_key`, and both are inserted with `ON CONFLICT DO NOTHING`. Dedup keys are derived from the triggering fact (a deployment result id, a hash of the webhook body), never from the current time.

**Two independent flows.** Bitbucket pull request events drive Trello cards (`pullrequest` → `trello`); Bamboo deployment results drive Telegram messages (`deployment` → `notify`). They are not correlated — no attempt is made to say "this PR reached STAGE". A Trello card is one pull request, keyed on `(project_key, repo_slug, pr_id)`.

Jira is read-only decoration (`jira`): it supplies an issue summary for card titles and is strictly best-effort. It must never be able to fail a card.

`work` holds what both journals share: `ProcessingStatus`, `RetryPolicy`, `RetryAfterException`, worker scheduling. One handler per `EventSource` and one sender per `OutboundTarget` — the processors reject ambiguity at startup rather than silently picking the first bean.

Integrations are individually switchable (`infra-manager.<name>.enabled`, and `infra-manager.bamboo.source`). While an integration is off its tasks are still queued and then marked `SKIPPED`, so a misconfiguration is visible in the table instead of silently dropping work. Each integration's config class validates its own credentials in its constructor, so a missing secret fails at startup rather than at the first real event.

### Boot 4 specifics that have already bitten

**Spring Boot 4.1.1 / Spring Framework 7.0.9 / Java 17.** Guidance written for Boot 3 often does not apply. Concretely:

- **Auto-configuration is split into per-technology modules.** `flyway-core` alone gives you the library but no auto-configuration, and migrations silently never run — you need `org.springframework.boot:spring-boot-flyway`. Likewise `@AutoConfigureMockMvc` now lives in `org.springframework.boot.webmvc.test.autoconfigure` and needs `spring-boot-starter-webmvc-test`.
- **Testcontainers 2.x.** Boot 4.1.1 manages 2.0.5, where every module artifactId is prefixed: `testcontainers-junit-jupiter`, `testcontainers-postgresql`. The Java packages are unchanged.
- **Jackson 3.** The mapper is `tools.jackson.databind.ObjectMapper` (`ObjectMapper` is abstract; use `JsonMapper.builder()`), but annotations still come from `com.fasterxml.jackson.annotation`. Its exceptions are unchecked.
- **JUnit 6** (`junit-jupiter` 6.0.3). The `org.junit.jupiter.api` package is unchanged but the platform is a major version ahead of most examples.
- Java 17 is Boot 4's baseline; the toolchain here is Corretto 17.0.10 via `JAVA_HOME`, and `java` is not on the bash `PATH`.

**HTTP clients are declarative `@HttpExchange` interfaces**, wired explicitly with `HttpServiceProxyFactory` rather than `@ImportHttpServices` groups — each integration has its own base URL and auth scheme, so there is no shared group to configure.

**Configuration property names cannot contain colons.** Bitbucket event keys like `pr:opened` therefore cannot be map keys; the lifecycle mapping is a list of `{event, list}` pairs instead. As a map it would need `"[pr:opened]": Review` in YAML, which reads like a typo and binds to nothing once someone "corrects" it.

Configuration lives in `src/main/resources/application.yaml` (YAML, not `.properties`). Secrets come from environment variables only — see `.env.example` and `docs/runbook.md`, which documents the manual setup in Trello, Telegram, Bamboo, Bitbucket and Jira.

`HELP.md` is Initializr-generated boilerplate and is gitignored — it is not project documentation, and nothing should be added to it.
