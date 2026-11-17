package com.deadlinedrop.service;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Decides whether an exception means "the database cannot be reached" rather than a bug. The
 * commit step issues a signed receipt in exactly these cases, and the error handler answers them
 * with 503; both use this one definition so they cannot drift apart.
 */
public final class DatabaseOutage {

    /** SQL standard class 08: connection exception (08001 cannot connect, 08003 closed, 08006 failure). */
    private static final String SQLSTATE_CONNECTION_EXCEPTION_CLASS = "08";

    /**
     * HikariCP (7.0.2) throws this, with no SQLState, from a connection it has already evicted
     * because a statement failed with a class-08 error. Spring's TransactionTemplate then reports
     * the failed rollback instead of the original error, so this is all that is left to recognise.
     * DatabaseDownIT and ReceiptIT run against the real pool and fail if a Hikari upgrade changes it.
     */
    private static final String HIKARI_EVICTED_CONNECTION_MESSAGE = "Connection is closed";

    private DatabaseOutage() {
    }

    /**
     * True for a pool timeout ({@link CannotCreateTransactionException},
     * {@link CannotGetJdbcConnectionException}), a resource failure
     * ({@link DataAccessResourceFailureException}, {@link TransientDataAccessResourceException}),
     * or any exception with a JDBC connection failure in its cause chain.
     */
    public static boolean isOutage(Throwable ex) {
        if (ex instanceof CannotCreateTransactionException
                || ex instanceof CannotGetJdbcConnectionException
                || ex instanceof DataAccessResourceFailureException
                || ex instanceof TransientDataAccessResourceException) {
            return true;
        }
        return hasConnectionFailureCause(ex);
    }

    /** True if a JDBC connection failure (SQLState class 08, or Hikari's evicted connection) is in the cause chain. */
    public static boolean hasConnectionFailureCause(Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = ex; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith(SQLSTATE_CONNECTION_EXCEPTION_CLASS)) {
                    return true;
                }
                if (state == null && HIKARI_EVICTED_CONNECTION_MESSAGE.equals(sql.getMessage())) {
                    return true;
                }
            }
        }
        return false;
    }
}
