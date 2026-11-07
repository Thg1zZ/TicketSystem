package dev.thiagogomes.ticketsystem.shared.error;

import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaSystemException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    @Test
    void lostConnectionIsRecognizedAnywhereInTheCauseChain() {
        // What a query on a dead connection surfaces: the failed rollback replaced the original error
        var failedRollback = new JpaSystemException(new org.hibernate.TransactionException("Unable to rollback",
                new SQLException("Connection is closed")));

        assertThat(ApiExceptionHandler.isConnectivityFailure(failedRollback)).isTrue();
        assertThat(ApiExceptionHandler.isConnectivityFailure(
                new JpaSystemException(new RuntimeException(new SQLException("I/O error", "08006"))))).isTrue();
    }

    @Test
    void otherDatabaseErrorsAreStillBugs() {
        assertThat(ApiExceptionHandler.isConnectivityFailure(
                new JpaSystemException(new RuntimeException(new SQLException("syntax error", "42601"))))).isFalse();
    }
}
