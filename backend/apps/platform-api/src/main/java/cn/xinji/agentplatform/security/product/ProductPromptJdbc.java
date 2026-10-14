package cn.xinji.agentplatform.security.product;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact datasource and connection checks; transaction ownership belongs to the calling application. */
final class ProductPromptJdbc {
    final JdbcTemplate db;
    final DataSource source;
    private final DataSourceTransactionManager manager;
    private final String url, schema, user;
    ProductPromptJdbc(JdbcTemplate db, DataSourceTransactionManager manager) {
        this.db = Objects.requireNonNull(db); this.source = Objects.requireNonNull(db.getDataSource());
        this.manager = Objects.requireNonNull(manager);
        if (manager.getDataSource() != source) throw ProductPromptRecords.invalid();
        try (Connection connection = source.getConnection()) {
            url = connection.getMetaData().getURL(); schema = connection.getSchema(); user = connection.getMetaData().getUserName();
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Prompt datasource unavailable", failure); }
    }
    void requireTransaction() {
        if (manager.getDataSource() != source || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !(TransactionSynchronizationManager.getResource(source) instanceof ConnectionHolder holder))
            throw ProductPromptRecords.unavailable();
        try {
            Connection connection = holder.getConnection();
            if (!holder.isSynchronizedWithTransaction() || connection.isClosed() || connection.getAutoCommit()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED
                    || !Objects.equals(url, connection.getMetaData().getURL())
                    || !Objects.equals(schema, connection.getSchema())
                    || !Objects.equals(user, connection.getMetaData().getUserName())) throw ProductPromptRecords.unavailable();
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Prompt transaction unavailable", failure); }
    }
    void requireSeparate(ProductPromptJdbc other) {
        if (source == other.source || Objects.equals(url, other.url) && Objects.equals(schema, other.schema)
                && Objects.equals(user, other.user)) throw ProductPromptRecords.invalid();
    }
    Object requireNoManagedTransaction() {
        if (manager.getDataSource() != source) throw ProductPromptRecords.unavailable();
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (resource == null) return null;
        // A main transaction can synchronize an authority autocommit reader. Suspension
        // releases its idle connection and later restores the same, possibly empty, holder.
        if (!(resource instanceof ConnectionHolder holder)
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !holder.isSynchronizedWithTransaction() || holder.isRollbackOnly() || holder.isOpen())
            throw ProductPromptRecords.unavailable();
        Connection connection = DataSourceUtils.getConnection(source);
        try {
            if (TransactionSynchronizationManager.getResource(source) != holder
                    || holder.getConnection() != connection || connection.isClosed() || !connection.getAutoCommit()
                    || !Objects.equals(url, connection.getMetaData().getURL())
                    || !Objects.equals(schema, connection.getSchema())
                    || !Objects.equals(user, connection.getMetaData().getUserName())) throw ProductPromptRecords.unavailable();
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("Prompt reader unavailable", failure);
        } finally {
            DataSourceUtils.releaseConnection(connection, source);
        }
        if (TransactionSynchronizationManager.getResource(source) != holder || holder.isOpen())
            throw ProductPromptRecords.unavailable();
        return holder;
    }
    void markRollbackOnly() {
        requireTransaction();
        ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setRollbackOnly();
    }
    static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Instant instant(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column); return value == null ? null : value.toInstant();
    }
}
