package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.util.Set;
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
@EnabledIfEnvironmentVariable(named="DM8_JDBC_URL",matches=".+")
class Dm8TrademarkSearchHttpIntegrationTest {
    private static final String URL=System.getenv("DM8_JDBC_URL"), USER=System.getenv("DM8_JDBC_USER"), PASSWORD=System.getenv("DM8_JDBC_PASSWORD"), REQUESTER="v0-authorized-user";
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){initialize();r.add("consultation.storage",()->"memory");r.add("enforcement.storage",()->"memory");r.add("patent-search.storage",()->"memory");r.add("trademark-search.storage",()->"dm8");r.add("spring.datasource.url",()->URL);r.add("spring.datasource.username",()->USER);r.add("spring.datasource.password",()->PASSWORD);r.add("spring.datasource.driver-class-name",()->"dm.jdbc.driver.DmDriver");}
    @AfterAll void cleanup() throws Exception {try(Connection c=connection()){cleanupTrademark(c);runIgnoring(c,"db/dm8/U001__consultation_mvp_v0.sql");}}
    @Test void routesHttpThroughDm8AndRetainsIdempotentHistory() throws Exception {String body="{\"inputMode\":\"TEXT\",\"name\":\"清源\",\"categories\":[\"3\"],\"goodsServices\":[\"化妆品\"],\"dimensions\":[\"TEXT\",\"PHONETIC\",\"SEMANTIC\"],\"scope\":{\"regions\":[\"CN\"]}}";JsonNode created=create("dm8-trademark",body);JsonNode replay=create("dm8-trademark",body);assertEquals(created.get("taskId").asText(),replay.get("taskId").asText());JsonNode complete=awaitTerminal(created.get("taskId").asText());assertEquals("SUCCEEDED",complete.get("status").asText());assertEquals("NOT_ANALYZED",complete.get("candidates").get(0).get("dimensions").get(2).get("state").asText());String history=mvc.perform(get("/api/v0/trademark-search-tasks").header("X-V0-Requester",REQUESTER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertEquals(1,mapper.readTree(history).size());}
    private JsonNode create(String key,String body)throws Exception{return mapper.readTree(mvc.perform(post("/api/v0/trademark-search-tasks").header("X-V0-Requester",REQUESTER).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());}
    private JsonNode awaitTerminal(String id)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();JsonNode body;do{body=mapper.readTree(mvc.perform(get("/api/v0/trademark-search-tasks/{id}",id).header("X-V0-Requester",REQUESTER)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());if(Set.of("SUCCEEDED","PARTIAL","FAILED","CANCELLED").contains(body.get("status").asText()))return body;Thread.sleep(20);}while(System.nanoTime()<end);throw new AssertionError("task did not finish");}
    private static void initialize(){try{Class.forName("dm.jdbc.driver.DmDriver");try(Connection c=connection()){cleanupTrademark(c);runIgnoring(c,"db/dm8/U001__consultation_mvp_v0.sql");run(c,"db/dm8/V001__consultation_mvp_v0.sql");}}catch(Exception e){throw new IllegalStateException(e);}}
    private static Connection connection()throws SQLException{return DriverManager.getConnection(URL,USER,PASSWORD);} private static void cleanupTrademark(Connection c)throws SQLException{try(Statement s=c.createStatement()){for(String t:new String[]{"con_trademark_search_dimension","con_trademark_search_candidate","con_trademark_search_task"})try{s.execute("DROP TABLE "+t);}catch(SQLException ignored){}}} private static void run(Connection c,String file)throws Exception{try(Statement s=c.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())s.execute(sql);}} private static void runIgnoring(Connection c,String file)throws Exception{try{run(c,file);}catch(SQLException ignored){}}
}
