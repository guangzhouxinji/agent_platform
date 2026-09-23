package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import cn.xinji.agentplatform.consultation.caselibrary.adapter.out.dm8.Dm8CaseAnnotationWorkflowStore;
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
class Dm8CaseAnnotationHttpIntegrationTest {
    private static final String URL=System.getenv("DM8_JDBC_URL"), USER=System.getenv("DM8_JDBC_USER"), PASSWORD=System.getenv("DM8_JDBC_PASSWORD"), ACTOR="v0-authorized-user";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        initialize();
        registry.add("consultation.storage",()->"memory"); registry.add("patent-search.storage",()->"memory"); registry.add("trademark-search.storage",()->"memory");
        registry.add("case-annotation.storage",()->"dm8"); registry.add("spring.datasource.url",()->URL); registry.add("spring.datasource.username",()->USER);
        registry.add("spring.datasource.password",()->PASSWORD); registry.add("spring.datasource.driver-class-name",()->"dm.jdbc.driver.DmDriver");
    }

    @AfterAll void cleanup() throws Exception { try(Connection connection=connection()){runIgnoring(connection,"db/dm8/U004__case_annotation_v0.sql");} }

    @Test void routesHttpThroughServiceAndDm8AndRestoresPublishedHistory() throws Exception {
        String extract="{\"caseId\":\"CASE-SYN-003\",\"sourceVersion\":\"v1\",\"config\":\"SYNTHETIC_EXTRACT_V1\"}";
        JsonNode task=mapper.readTree(mvc.perform(post("/api/v0/case-annotations/extractions").header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content(extract)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode replay=mapper.readTree(mvc.perform(post("/api/v0/case-annotations/extractions").header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content(extract)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(task.get("taskId").asText(),replay.get("taskId").asText()); String id=task.get("annotationId").asText();
        mvc.perform(post("/api/v0/case-annotations/{id}/revisions",id).header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"fact\":\"DM8核对事实\",\"issue\":\"DM8争议焦点\",\"holding\":\"DM8合成要点\",\"sourceLocation\":\"第2页第3段\",\"reason\":\"真实库事务验证\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(post("/api/v0/case-annotations/{id}/submit",id).header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}")).andExpect(status().isOk());
        mvc.perform(post("/api/v0/case-annotations/{id}/reviews",id).header("X-V0-Actor",ACTOR).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3,\"action\":\"PUBLISH\",\"reason\":\"位置已核对\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PUBLISHED"));
        mvc.perform(get("/api/v0/case-annotations/cases/CASE-SYN-003").header("X-V0-Actor",ACTOR)).andExpect(status().isOk()).andExpect(jsonPath("$.storageMode").value("DM8")).andExpect(jsonPath("$.annotations.length()").value(4)).andExpect(jsonPath("$.audits.length()").value(4));
        Dm8CaseAnnotationWorkflowStore restarted=new Dm8CaseAnnotationWorkflowStore(()->{try{return connection();}catch(SQLException failure){throw new IllegalStateException(failure);}});
        assertEquals("DM8核对事实",restarted.published("CASE-SYN-003","v1").orElseThrow().fact());
        assertEquals(4,restarted.annotationsForCase("CASE-SYN-003").size());
    }

    private static void initialize(){try{Class.forName("dm.jdbc.driver.DmDriver");try(Connection connection=connection()){runIgnoring(connection,"db/dm8/U004__case_annotation_v0.sql");run(connection,"db/dm8/V004__case_annotation_v0.sql");}}catch(Exception failure){throw new IllegalStateException(failure);}}
    private static Connection connection()throws SQLException{return DriverManager.getConnection(URL,USER,PASSWORD);}
    private static void run(Connection connection,String file)throws Exception{try(Statement statement=connection.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())statement.execute(sql);}}
    private static void runIgnoring(Connection connection,String file)throws Exception{try{run(connection,file);}catch(SQLException ignored){}}
}
