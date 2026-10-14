package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CaseWorkHttpsLifecycleTest {
    @Test void realSignedJwtHttpsLifecycleAndRestartPreserveAllVersionsAndEvidence()throws Exception{
        try(var fixture=new CaseApiFixture(false)){
            var before=CaseApiLifecycle.run(fixture);fixture.restart();var after=fixture.current();assertEquals(before,after);
            assertEquals(17,fixture.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_revision",Integer.class));
            assertEquals(17,fixture.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_receipt",Integer.class));
            assertEquals(17,fixture.databases.db.queryForObject("SELECT COUNT(*) FROM adj2_case_audit",Integer.class));
        }
    }
}
