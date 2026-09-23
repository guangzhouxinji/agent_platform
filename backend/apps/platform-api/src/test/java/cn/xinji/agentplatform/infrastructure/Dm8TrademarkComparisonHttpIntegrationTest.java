package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.consultation.trademarkcomparison.adapter.out.dm8.Dm8TrademarkComparisonTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc @TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named="DM8_JDBC_URL", matches=".+")
class Dm8TrademarkComparisonHttpIntegrationTest {
    private static final String URL=System.getenv("DM8_JDBC_URL"), USER=System.getenv("DM8_JDBC_USER"), PASSWORD=System.getenv("DM8_JDBC_PASSWORD"), ACTOR="v0-authorized-user";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        initialize();
        registry.add("consultation.storage",()->"memory"); registry.add("patent-search.storage",()->"memory");
        registry.add("trademark-search.storage",()->"memory"); registry.add("case-annotation.storage",()->"memory");
        registry.add("patent-comparison.storage",()->"memory"); registry.add("trademark-comparison.storage",()->"dm8");
        registry.add("spring.datasource.url",()->URL); registry.add("spring.datasource.username",()->USER);
        registry.add("spring.datasource.password",()->PASSWORD); registry.add("spring.datasource.driver-class-name",()->"dm.jdbc.driver.DmDriver");
    }

    @AfterAll void cleanup() throws Exception { try(Connection connection=connection()){runIgnoring(connection,"db/dm8/U006__trademark_comparison_v0.sql");} }

    @Test void routesHttpThroughDm8AndRestoresExactTask() throws Exception {
        String body="{\"purpose\":\"AUXILIARY_COMPARISON\",\"target\":{\"sourceId\":\"TM-SYN-001\",\"version\":\"synthetic-v1\"},\"candidates\":[{\"sourceId\":\"TM-SYN-002\",\"version\":\"synthetic-v1\"},{\"sourceId\":\"MISSING\",\"version\":\"v1\"}],\"confirmedCategories\":[\"3\"],\"confirmedGoodsServices\":[\"化妆品\",\"洗发液\"],\"dimensions\":[\"TEXT\",\"PHONETIC\",\"SEMANTIC\",\"IMAGE\",\"OVERALL_VISUAL\",\"GOODS_SERVICES\"]}";
        JsonNode created=mapper.readTree(mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","dm8-http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath("$.pairs[0].dimensions.length()").value(6))
                .andExpect(jsonPath("$.pairs[1].missingItems[0]").value("DOCUMENT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString());
        JsonNode replay=mapper.readTree(mvc.perform(post("/api/v0/trademark-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","dm8-http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(created.get("taskId").asText(), replay.get("taskId").asText());

        Dm8TrademarkComparisonTaskRepository restarted=new Dm8TrademarkComparisonTaskRepository(()->{try{return connection();}catch(SQLException failure){throw new IllegalStateException(failure);}});
        var restored=restarted.findById(created.get("taskId").asText()).orElseThrow();
        assertEquals("PARTIAL",restored.status()); assertEquals(2,restored.pairs().size()); assertEquals(6,restored.pairs().getFirst().dimensions().size());
        mvc.perform(get("/api/v0/trademark-comparisons/tasks/{id}",restored.taskId()).header("X-V0-Actor",ACTOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inputSnapshot.confirmedGoodsServices[1]").value("洗发液"));
    }

    private static void initialize(){try{Class.forName("dm.jdbc.driver.DmDriver");try(Connection connection=connection()){runIgnoring(connection,"db/dm8/U006__trademark_comparison_v0.sql");run(connection,"db/dm8/V006__trademark_comparison_v0.sql");}}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static Connection connection()throws SQLException{return DriverManager.getConnection(URL,USER,PASSWORD);}
    private static void run(Connection connection,String file)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void runIgnoring(Connection connection,String file)throws Exception{try{run(connection,file);}catch(SQLException ignored){}}
}
