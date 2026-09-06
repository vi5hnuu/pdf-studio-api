package com.vishnu.pdf_studio_api.pdfstudioapi.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Boots the application with the Flyway baseline applied and {@code ddl-auto=validate}, the
 * same combination production runs.
 *
 * <p>Without this, drift between the migration and the JPA mappings is only discovered when
 * the real service fails to start — which is exactly what happened when {@code user_id} was
 * widened in SQL but left at 40 characters in the entities. H2's MySQL compatibility mode is
 * close enough to catch column, type and constraint mismatches.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.datasource.url=jdbc:h2:mem:schemacheck;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
class SchemaValidatesTest {

    @Test
    @DisplayName("the Flyway baseline satisfies every JPA mapping")
    void schemaMatchesEntities() {
        // Reaching here means Hibernate validated all entities against the migrated schema.
    }
}
