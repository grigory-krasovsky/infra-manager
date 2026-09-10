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

## Architecture

This is a Spring Initializr skeleton with no domain code yet. The entire application is `InfraManagerApplication` (`src/main/java/com/example/inframanager/`), a bare `@SpringBootApplication` main class, plus a `contextLoads` smoke test. Everything beyond that is still to be written; new code belongs under the `com.example.inframanager` base package so component scanning picks it up.

Two facts that shape what you write here:

**No web starter.** The only compile dependency is `spring-boot-starter` — not `-web`. `spring-boot:run` therefore boots the context, logs "Started InfraManagerApplication", and exits immediately, since nothing holds a non-daemon thread. This is expected, not a failure. To make the process stay alive you must add `spring-boot-starter-web`/`-webflux`, or supply a `CommandLineRunner`/`ApplicationRunner` for a batch-style app. Decide which shape this is before adding features.

**Spring Boot 4.1.1 / Spring Framework 7.0.9 / Java 17.** This is the Boot 4 generation, so guidance and snippets written for Boot 3 may not apply. Notably, tests run on **JUnit 6** (`junit-jupiter` 6.0.3) — the `org.junit.jupiter.api` package is unchanged but the platform is a major version ahead of the JUnit 5 examples most references show. Java 17 is Boot 4's baseline; the toolchain here is Corretto 17.0.10 via `JAVA_HOME`, and `java` is not on the bash `PATH`.

Configuration lives in `src/main/resources/application.yaml` (YAML, not `.properties`), currently only setting `spring.application.name`.

`HELP.md` is Initializr-generated boilerplate and is gitignored — it is not project documentation, and nothing should be added to it.
