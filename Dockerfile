# syntax=docker/dockerfile:1

# ---- build ----------------------------------------------------------------
FROM amazoncorretto:17-alpine AS build
WORKDIR /build

# Warm the dependency cache in its own layer. Best-effort: the Maven wrapper
# resolves some plugins lazily, so a failure here must not fail the build --
# the real `package` below resolves whatever is still missing.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && (./mvnw -B -q dependency:go-offline || true)

COPY src/ src/
RUN ./mvnw -B -DskipTests package

# ---- runtime --------------------------------------------------------------
FROM amazoncorretto:17-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build /build/target/infra-manager-*.jar app.jar

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
