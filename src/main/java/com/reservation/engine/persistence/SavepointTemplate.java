package com.reservation.engine.persistence;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs a statement inside a JDBC savepoint of the current transaction so that a constraint violation
 * (unique / exclusion / check) can be rolled back locally and another candidate tried, instead of
 * aborting the whole PostgreSQL transaction.
 */
public class SavepointTemplate {
    private final DataSource dataSource;

    public SavepointTemplate(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public <T> Optional<T> tryWithSavepoint(Supplier<T> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Savepoints require an active transaction");
        }
        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            Savepoint sp = con.setSavepoint();
            try {
                T result = action.get();
                con.releaseSavepoint(sp);
                return Optional.of(result);
            } catch (DataIntegrityViolationException e) {
                con.rollback(sp);
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Savepoint handling failed", e);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }

    /**
     * Runs {@code action} inside a savepoint and keeps its effects only if it returns a value. On an empty result
     * the savepoint is rolled back, which also releases every row lock the action acquired (e.g. rows locked by
     * {@code FOR UPDATE} that then failed the re-check), so the caller continues without stray locks.
     */
    public <T> Optional<T> keepIfPresent(Supplier<Optional<T>> action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Savepoints require an active transaction");
        }
        Connection con = DataSourceUtils.getConnection(dataSource);
        try {
            Savepoint sp = con.setSavepoint();
            Optional<T> result = action.get();
            if (result.isPresent()) {
                con.releaseSavepoint(sp);
            } else {
                con.rollback(sp);
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException("Savepoint handling failed", e);
        } finally {
            DataSourceUtils.releaseConnection(con, dataSource);
        }
    }
}
