package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.trademarksearch.adapter.out.dm8.Dm8TrademarkSearchTaskRepository;
import cn.xinji.agentplatform.consultation.trademarksearch.domain.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;

class Dm8TrademarkSearchTaskRepositoryIntegrationTest {
    private String url, user, password;
    @BeforeEach void initialize() throws Exception { url=System.getenv("DM8_JDBC_URL"); user=System.getenv("DM8_JDBC_USER"); password=System.getenv("DM8_JDBC_PASSWORD"); Assumptions.assumeTrue(url!=null&&user!=null&&password!=null,"DM8 environment is not configured"); Class.forName("dm.jdbc.driver.DmDriver"); try(Connection c=connection()){cleanupTrademark(c);run(c,"db/dm8/V001__consultation_mvp_v0.sql");} }
    @AfterEach void cleanup() throws Exception { if(url!=null)try(Connection c=connection()){cleanupTrademark(c);runIgnoring(c,"db/dm8/U001__consultation_mvp_v0.sql");} }

    @Test void roundTripsSnapshotStableOrderAndDimensionsThroughNewRepository() {
        var repository=repository(); var queued=task("roundtrip","key"); repository.save(queued);
        var completed=repository.update(queued.taskId(),current->current.succeeded("scope",2,false,List.of(candidate("TM-2"),candidate("TM-1")),"synthetic only",false,at(2)));
        var restored=repository().findById(completed.taskId()).orElseThrow(); assertEquals(completed,restored); assertEquals(List.of("TM-2","TM-1"),restored.candidates().stream().map(TrademarkSearchCandidate::sourceId).toList()); assertEquals(TrademarkSearchCandidate.State.NOT_ANALYZED,restored.candidates().getFirst().dimensions().get(1).state());
    }
    @Test void failedCandidateReplacementRollsBackTaskVersionAndSnapshot() {
        var repository=repository(); var queued=task("rollback","rollback-key"); repository.save(queued); var duplicate=candidate("TM-DUP");
        assertThrows(IllegalStateException.class,()->repository.update(queued.taskId(),current->current.succeeded("scope",2,false,List.of(duplicate,duplicate),"rollback",false,at(2))));
        assertEquals(queued,repository().findById(queued.taskId()).orElseThrow()); assertEquals(0,scalar("SELECT COUNT(*) FROM con_trademark_search_candidate")); assertEquals(0,scalar("SELECT row_version FROM con_trademark_search_task WHERE task_id='"+queued.taskId()+"'"));
    }
    @Test void restartFailsInterruptedTaskWithoutLosingInputSnapshot() {
        var first=repository(); var queued=task("restart","restart-key"); first.save(queued); first.update(queued.taskId(),current->current.processing(at(2)));
        var restarted=repository(); assertEquals(1,restarted.failInterruptedTasks(at(3))); var recovered=restarted.findById(queued.taskId()).orElseThrow(); assertEquals(TrademarkSearchTask.Status.FAILED,recovered.status()); assertEquals("RESTART_INTERRUPTED",recovered.errorCode()); assertEquals(queued.inputSnapshot(),recovered.inputSnapshot());
    }
    private TrademarkSearchTask task(String seed,String key){var command=new TrademarkSearchCommand(TrademarkSearchCommand.InputMode.TEXT,"清源",null,List.of("3"),List.of("化妆品"),List.of(TrademarkSearchCommand.Dimension.TEXT,TrademarkSearchCommand.Dimension.SEMANTIC),new TrademarkSearchCommand.SearchScope(List.of("有效"),List.of("CN"),List.of("SYNTHETIC_TRADEMARK_CATALOG")),1,20);return TrademarkSearchTask.queued(UUID.nameUUIDFromBytes(seed.getBytes()).toString(),"v0-authorized-user",key,"fingerprint-"+seed,command,at(1));}
    private static TrademarkSearchCandidate candidate(String id){return new TrademarkSearchCandidate(id,"REG-"+id,"清源",List.of("3"),List.of("化妆品"),"有效","CN","SYNTHETIC_TRADEMARK_CATALOG","synthetic-v1","2026-09-16","值得进一步比对",List.of(new TrademarkSearchCandidate.DimensionResult(TrademarkSearchCommand.Dimension.TEXT,TrademarkSearchCandidate.State.EXECUTED,"文字"),new TrademarkSearchCandidate.DimensionResult(TrademarkSearchCommand.Dimension.SEMANTIC,TrademarkSearchCandidate.State.NOT_ANALYZED,"未配置")));}
    private Dm8TrademarkSearchTaskRepository repository(){return new Dm8TrademarkSearchTaskRepository(()->{try{return connection();}catch(SQLException e){throw new IllegalStateException(e);}});} private Connection connection()throws SQLException{return DriverManager.getConnection(url,user,password);} private static Instant at(int minute){return Instant.parse("2026-09-16T01:0"+minute+":00Z");} private int scalar(String sql){try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){r.next();return r.getInt(1);}catch(SQLException e){throw new IllegalStateException(e);}}
    private static void run(Connection c,String file)throws Exception{try(Statement s=c.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())s.execute(sql);}} private static void runIgnoring(Connection c,String file)throws Exception{try{run(c,file);}catch(SQLException ignored){}} private static void cleanupTrademark(Connection c)throws SQLException{try(Statement s=c.createStatement()){for(String table:List.of("con_trademark_search_dimension","con_trademark_search_candidate","con_trademark_search_task"))try{s.execute("DROP TABLE "+table);}catch(SQLException ignored){}}}
}
