package cn.xinji.agentplatform.infrastructure;

import static org.junit.jupiter.api.Assertions.*;

import cn.xinji.agentplatform.consultation.adapter.out.dm8.Dm8ConsultationStore;
import cn.xinji.agentplatform.consultation.domain.ConsultationCandidate;
import cn.xinji.agentplatform.consultation.domain.ConsultationOutcome;
import cn.xinji.agentplatform.consultation.domain.ConsultationSourceLink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class Dm8ConsultationStoreTest {
    @Test void writesAndReadsSyntheticCitedAnswerByIdempotencyKey() throws Exception {
        String url=System.getenv("DM8_JDBC_URL"), user=System.getenv("DM8_JDBC_USER"), password=System.getenv("DM8_JDBC_PASSWORD");
        Assumptions.assumeTrue(url!=null&&user!=null&&password!=null, "DM8 environment is not configured");
        Class.forName("dm.jdbc.driver.DmDriver");
        try(Connection c=DriverManager.getConnection(url,user,password)) { executeIgnoring(c,"db/dm8/U001__consultation_mvp_v0.sql"); execute(c,"db/dm8/V001__consultation_mvp_v0.sql"); }
        var store=new Dm8ConsultationStore(() -> { try { return DriverManager.getConnection(url,user,password); } catch(SQLException e) { throw new IllegalStateException(e); } });
        var result=new ConsultationOutcome("con-store", "answer-store-1", 1, "fp-store", "ANSWER_READY", List.of(), List.of(new ConsultationCandidate("V0-SYN-PAT-001","v1","权利要求 1","水性粘结剂")), List.of(new ConsultationSourceLink("link-store-1", "V0-SYN-PAT-001", "v1", "权利要求 1")), "辅助回答", "不构成正式意见");
        store.save("v0-authorized-user", "store-key", "水性粘结剂技术方案", "TECHNICAL_REFERENCE", result);
        var restored=store.findByIdempotency("v0-authorized-user", "store-key").orElseThrow();
        assertEquals("fp-store", restored.fingerprint()); assertEquals("ANSWER_READY", restored.outcome().type());
        assertEquals("V0-SYN-PAT-001", restored.outcome().candidates().getFirst().sourceId());
        assertEquals("link-store-1", restored.outcome().sourceLinks().getFirst().sourceLinkId());
        var refreshed = new ConsultationOutcome("con-store", "answer-store-2", 2, "fp-store", "PARTIAL_RESULT", List.of(), List.of(new ConsultationCandidate("V0-SYN-PAT-001", "v1", "权利要求 1", "水性粘结剂")), List.of(), "刷新后的辅助回答", "已排除不可用来源");
        store.saveRefresh("v0-authorized-user", refreshed);
        var latest = store.findByConsultationId("con-store").orElseThrow().outcome();
        assertEquals("PARTIAL_RESULT", latest.type());
        assertEquals(1, latest.candidates().size());
        assertEquals("answer-store-1",store.findByIdempotency("v0-authorized-user","store-key").orElseThrow().outcome().answerId());
        var duplicate=new ConsultationOutcome("con-rollback","answer-rollback",1,"fp-rollback","ANSWER_READY",List.of(),List.of(),List.of(),"不应提交","测试回滚");
        assertThrows(IllegalStateException.class,()->store.save("v0-authorized-user","store-key","重复键","TECHNICAL_REFERENCE",duplicate));
        assertTrue(store.findByConsultationId("con-rollback").isEmpty());
        try(Connection c=DriverManager.getConnection(url,user,password)) { execute(c,"db/dm8/U001__consultation_mvp_v0.sql"); }
    }
    private static void execute(Connection c,String file) throws Exception { try(Statement s=c.createStatement()){for(String sql:Files.readString(Path.of(file)).split(";\\s*(?:\\r?\\n|$)"))if(!sql.isBlank())s.execute(sql);} }
    private static void executeIgnoring(Connection c,String file) throws Exception { try { execute(c,file); } catch(SQLException ignored) {} }
}
