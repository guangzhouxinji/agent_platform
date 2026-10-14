package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCallback;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptStatementJdbcTemplateTest {
    @Test void actualStatementKeepsFiveSecondCapDespiteThirtySecondTransactionAndBufferedWatermark(){
        var source=ProductPromptWorkspaceAuthorizationFixture.source();
        var bounded=new ProductPromptStatementJdbcTemplate(source);
        JdbcTemplate buffered=ProductWatermarkJdbc.buffered(bounded);
        assertThat(buffered).isInstanceOf(ProductPromptStatementJdbcTemplate.class);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(30);
        tx.executeWithoutResult(status->{
            assertThat(bounded.execute((StatementCallback<Integer>)Statement::getQueryTimeout)).isEqualTo(5);
            assertThat(buffered.execute((StatementCallback<Integer>)Statement::getQueryTimeout)).isEqualTo(5);
            assertThat(buffered.execute((StatementCallback<Integer>)Statement::getFetchSize)).isEqualTo(256);
        });
    }

    @Test void actualStatementFloorsShorterRemainingTransactionTime(){
        var source=ProductPromptWorkspaceAuthorizationFixture.source();
        var bounded=new ProductPromptStatementJdbcTemplate(source);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(30);
        tx.executeWithoutResult(status->{
            var holder=(ConnectionHolder)TransactionSynchronizationManager.getResource(source);
            holder.setTimeoutInMillis(1800);
            assertThat(bounded.execute((StatementCallback<Integer>)Statement::getQueryTimeout)).isEqualTo(1);
        });
    }

    @Test void lessThanOneSecondRejectsBeforeStatementCallback(){
        var source=ProductPromptWorkspaceAuthorizationFixture.source();
        var bounded=new ProductPromptStatementJdbcTemplate(source);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(30);
        var called=new AtomicBoolean();
        tx.executeWithoutResult(status->{
            ((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).setTimeoutInMillis(500);
            assertThatThrownBy(()->bounded.execute((StatementCallback<Integer>)statement->{called.set(true);return 1;}))
                    .isInstanceOf(QueryTimeoutException.class);
            assertThat(called).isFalse();
        });
    }

    @Test void ordinaryWatermarkTemplateRetainsExistingSettingsAndTransactionTimeoutBehavior(){
        var source=ProductPromptWorkspaceAuthorizationFixture.source();var ordinary=new JdbcTemplate(source);
        ordinary.setQueryTimeout(7);ordinary.setMaxRows(13);ordinary.setIgnoreWarnings(false);
        var buffered=ProductWatermarkJdbc.buffered(ordinary);
        assertThat(buffered).isExactlyInstanceOf(JdbcTemplate.class);
        assertThat(buffered.getQueryTimeout()).isEqualTo(7);assertThat(buffered.getMaxRows()).isEqualTo(13);
        assertThat(buffered.isIgnoreWarnings()).isFalse();assertThat(buffered.getDataSource()).isSameAs(source);
        assertThat(buffered.execute((StatementCallback<Integer>)Statement::getQueryTimeout)).isEqualTo(7);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(30);
        tx.executeWithoutResult(status->assertThat(buffered.execute((StatementCallback<Integer>)Statement::getQueryTimeout)).isBetween(25,30));
    }
}
