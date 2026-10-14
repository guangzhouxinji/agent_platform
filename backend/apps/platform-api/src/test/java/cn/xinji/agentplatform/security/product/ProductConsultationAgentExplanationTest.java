package cn.xinji.agentplatform.security.product;

import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;

/** Inherited real SQL/dialogue contract scenarios; H2 is not a business Golden or DM8 claim. */
class ProductConsultationAgentExplanationTest extends ProductConsultationModelFixture {
    ProductConsultationAgentExplanationTest() { super(memorySource()); }
    private static javax.sql.DataSource memorySource() {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");return source;
    }
}
