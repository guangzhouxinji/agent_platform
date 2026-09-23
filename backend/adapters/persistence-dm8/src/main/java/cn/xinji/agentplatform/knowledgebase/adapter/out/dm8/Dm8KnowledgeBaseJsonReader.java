package cn.xinji.agentplatform.knowledgebase.adapter.out.dm8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test-synthetic")
@ConditionalOnProperty(name = "knowledge-base.provider", havingValue = "dm8")
public final class Dm8KnowledgeBaseJsonReader {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Dm8KnowledgeBaseJsonReader(DataSource dataSource, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.mapper = mapper;
    }

    public List<JsonNode> records(String domain) {
        return jdbc.query("SELECT normalized_payload FROM kb_record_version WHERE domain_code=? AND data_mode='PUBLIC_LOCAL' ORDER BY stable_record_key",
                (rs, row) -> parse(rs.getString(1)), domain);
    }

    private JsonNode parse(String payload) {
        try { return mapper.readTree(payload); }
        catch (Exception failure) { throw new IllegalStateException("Invalid local knowledge-base payload", failure); }
    }
}
