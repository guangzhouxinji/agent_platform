package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.consultation.patentcomparison.adapter.out.dm8.Dm8PatentComparisonTaskRepository;
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
class Dm8PatentComparisonHttpIntegrationTest {
    private static final String URL=System.getenv("DM8_JDBC_URL"), USER=System.getenv("DM8_JDBC_USER"), PASSWORD=System.getenv("DM8_JDBC_PASSWORD"), ACTOR="v0-authorized-user";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        initialize();
        registry.add("consultation.storage",()->"memory"); registry.add("patent-search.storage",()->"memory");
        registry.add("trademark-search.storage",()->"memory"); registry.add("case-annotation.storage",()->"memory");
        registry.add("patent-comparison.storage",()->"dm8"); registry.add("spring.datasource.url",()->URL);
        registry.add("spring.datasource.username",()->USER); registry.add("spring.datasource.password",()->PASSWORD);
        registry.add("spring.datasource.driver-class-name",()->"dm.jdbc.driver.DmDriver");
    }

    @AfterAll void cleanup() throws Exception { try(Connection connection=connection()){runIgnoring(connection,"db/dm8/U005__patent_comparison_v0.sql");} }

    @Test void routesHttpThroughDm8AndRestoresExactTask() throws Exception {
        String body="{\"purpose\":\"CLAIM_COMPARISON\",\"target\":{\"sourceId\":\"SYN-PAT-001\",\"version\":\"synthetic-v1\"},\"candidates\":[{\"sourceId\":\"SYN-PAT-003\",\"version\":\"synthetic-v1\"},{\"sourceId\":\"MISSING\",\"version\":\"v1\"}],\"confirmedFeatures\":[{\"featureId\":\"F1\",\"text\":\"双区烘干\",\"sourceLocation\":\"目标权利要求1\"},{\"featureId\":\"F2\",\"text\":\"水性粘结剂\",\"sourceLocation\":\"目标权利要求1\"}]}";
        JsonNode created=mapper.readTree(mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","dm8-http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL")).andExpect(jsonPath("$.pairs[0].mappings.length()").value(2)).andExpect(jsonPath("$.pairs[1].unexecutedItems[0]").value("DOCUMENT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString());
        JsonNode replay=mapper.readTree(mvc.perform(post("/api/v0/patent-comparisons/tasks").header("X-V0-Actor",ACTOR).header("Idempotency-Key","dm8-http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(created.get("taskId").asText(), replay.get("taskId").asText());

        Dm8PatentComparisonTaskRepository restarted=new Dm8PatentComparisonTaskRepository(()->{try{return connection();}catch(SQLException failure){throw new IllegalStateException(failure);}});
        var restored=restarted.findById(created.get("taskId").asText()).orElseThrow();
        assertEquals("PARTIAL",restored.status()); assertEquals(2,restored.pairs().size()); assertEquals(2,restored.pairs().getFirst().mappings().size());
        mvc.perform(get("/api/v0/patent-comparisons/tasks/{id}",restored.taskId()).header("X-V0-Actor",ACTOR))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inputSnapshot.confirmedFeatures[1].text").value("水性粘结剂"));
    }

    private static void initialize(){try{Class.forName("dm.jdbc.driver.DmDriver");try(Connection connection=connection()){runIgnoring(connection,"db/dm8/U005__patent_comparison_v0.sql");run(connection,"db/dm8/V005__patent_comparison_v0.sql");}}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static Connection connection()throws SQLException{return DriverManager.getConnection(URL,USER,PASSWORD);}
    private static void run(Connection connection,String file)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void runIgnoring(Connection connection,String file)throws Exception{try{run(connection,file);}catch(SQLException ignored){}}
}
