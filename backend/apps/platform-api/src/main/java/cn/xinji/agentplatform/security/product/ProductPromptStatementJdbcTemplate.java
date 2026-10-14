package cn.xinji.agentplatform.security.product;

import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Dedicated prompt JDBC instance; never mutates the application's shared template. */
final class ProductPromptStatementJdbcTemplate extends JdbcTemplate {
    ProductPromptStatementJdbcTemplate(DataSource source){super(source);}

    @Override protected void applyStatementSettings(Statement statement)throws SQLException {
        super.applyStatementSettings(statement);
        long remaining=5000;
        Object resource=TransactionSynchronizationManager.getResource(getDataSource());
        if(resource instanceof ConnectionHolder holder && holder.hasTimeout())
            remaining=Math.min(remaining,holder.getTimeToLiveInMillis());
        if(remaining<1000)throw new QueryTimeoutException("Prompt statement deadline reached");
        // Spring's ordinary applyTimeout replaces the configured query timeout with the
        // transaction TTL. Apply the smaller, rounded-down value after that replacement.
        statement.setQueryTimeout((int)(remaining/1000));
    }
}
