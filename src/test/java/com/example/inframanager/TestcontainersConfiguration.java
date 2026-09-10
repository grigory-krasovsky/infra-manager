package com.example.inframanager;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Backs integration tests with the same Postgres image docker-compose runs, so
 * Flyway migrations are exercised against the real target version rather than an
 * in-memory stand-in.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Must match the image tag in docker-compose.yml. */
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
