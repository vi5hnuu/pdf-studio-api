package com.vishnu.pdf_studio_api.pdfstudioapi.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots against real MySQL with Flyway applied and {@code ddl-auto=validate}.
 *
 * <p>{@link SchemaValidatesTest} does the same on H2, which is fast but not faithful: H2's
 * MySQL mode does not reproduce Hibernate's dialect-specific column mapping. Hibernate 6 maps
 * an enum to MySQL's native {@code enum(...)} type, which the {@code VARCHAR} in the migration
 * does not match — so the H2 test passed while the service refused to start against a real
 * database. Only the real engine catches that.
 *
 * <p>Skipped automatically where Docker is unavailable, so it never blocks a machine or a CI
 * job that has no daemon.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
})
@Testcontainers(disabledWithoutDocker = true)
class MySqlSchemaValidatesTest {

    @Container
    @SuppressWarnings("resource") // Testcontainers manages the lifecycle
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Test
    @DisplayName("the migration and the JPA mappings agree on real MySQL")
    void schemaMatchesEntitiesOnMySql() {
        // Reaching here means Flyway applied cleanly and Hibernate validated every mapping
        // against the engine production actually runs.
    }
}
