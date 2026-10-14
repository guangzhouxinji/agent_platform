package cn.xinji.agentplatform.security.product;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Private transport hint only: every row and current authorization check remains unchanged. */
final class ProductWatermarkJdbc {
    private ProductWatermarkJdbc() { }
    static JdbcTemplate buffered(JdbcTemplate original) {
        var source = Objects.requireNonNull(original.getDataSource());
        var result = original instanceof ProductPromptStatementJdbcTemplate
                ? new ProductPromptStatementJdbcTemplate(source) : new JdbcTemplate(source);
        result.setFetchSize(256);
        result.setMaxRows(original.getMaxRows());
        result.setQueryTimeout(original.getQueryTimeout());
        result.setIgnoreWarnings(original.isIgnoreWarnings());
        result.setSkipResultsProcessing(original.isSkipResultsProcessing());
        result.setSkipUndeclaredResults(original.isSkipUndeclaredResults());
        result.setResultsMapCaseInsensitive(original.isResultsMapCaseInsensitive());
        result.setExceptionTranslator(original.getExceptionTranslator());
        return result;
    }
}
