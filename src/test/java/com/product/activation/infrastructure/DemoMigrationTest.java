package com.product.activation.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoMigrationTest {
    @Test
    void seedsConsistentRequestsOnce() throws Exception {
        String url = "jdbc:h2:mem:seed-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        var flyway = Flyway.configure().dataSource(url, "sa", "")
                .locations("classpath:db/migration", "classpath:db/demo").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request")).isEqualTo(100);
            assertThat(count(connection, "SELECT COUNT(DISTINCT external_id) FROM activation_request")).isEqualTo(100);
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status = 'COMPLETED'")).isEqualTo(40);
            for (String status : new String[]{"PENDING", "PROCESSING", "FAILED"}) {
                assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status = '" + status + "'")).isEqualTo(20);
            }
            for (String consumer : new String[]{"demo", "sistema-a", "sistema-b", "sistema-c"}) {
                assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE consumer_id = '" + consumer + "'")).isEqualTo(25);
            }
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE created_at > updated_at")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status = 'PENDING' AND (attempts <> 0 OR failure_code IS NOT NULL OR lease_until IS NOT NULL)")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status IN ('COMPLETED', 'FAILED') AND (attempts < 1 OR lease_until IS NOT NULL)")).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP")).isEqualTo(10);
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status = 'PROCESSING' AND next_attempt_at > CURRENT_TIMESTAMP")).isEqualTo(5);
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status = 'PROCESSING' AND attempts = 5")).isEqualTo(5);
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request WHERE status = 'FAILED' AND failure_code IS NULL")).isZero();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(count(connection, "SELECT COUNT(*) FROM activation_request")).isEqualTo(100);
        }
    }

    private long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
