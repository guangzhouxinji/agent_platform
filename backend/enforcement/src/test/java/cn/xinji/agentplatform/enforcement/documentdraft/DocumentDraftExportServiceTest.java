package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Mechanical export-command C only; renderer bytes are NOT product DOCX or integration evidence. */
class DocumentDraftExportServiceTest {
    final DocumentDraftServiceTest.Fixture f=new DocumentDraftServiceTest.Fixture();
    final Input input=DocumentDraftServiceTest.input(Type.FACT_RECORD);
    final DocumentDraftWorkflow workflow=new DocumentDraftWorkflow(f.service,f,f,f,Clock.systemUTC(),new ObjectMapper());
    int renders;boolean broken,empty;
    final DocumentDraftExportService exports=new DocumentDraftExportService(f.service,f,f,f,new ExportRenderer(){
        public String version(){return "COMPONENT_RENDERER_NOT_DOCX";}
        public byte[] render(String actor,Draft draft){renders++;if(broken)throw new IllegalStateException("Injected render failure");return empty?new byte[0]:"COMPONENT_BYTES_NOT_DOCX".getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    },Clock.systemUTC());
    Draft generated(){f.narrative=true;f.generate(input,"generate");return f.service.read("actor","draft",1);}
    Draft approved(){var draft=generated();workflow.submit("actor","submit","draft",1,draft.contentSha256());workflow.review("reviewer","approve","draft",1,draft.contentSha256(),"APPROVED","Component decision not business approval");return draft;}
    @Test void exactApprovedExportReplaysOneArtifactWithoutRewritingContent() {
        var draft=approved();var result=exports.create("actor","export","draft",1,draft.contentSha256());
        assertEquals("EXPORT",result.operation());assertNotNull(result.exportId());assertNull(result.candidateId());
        assertEquals(result,exports.create("actor","export","draft",1,draft.contentSha256()));assertEquals(1,renders);assertEquals(1,f.exports.size());assertEquals(draft,f.service.read("actor","draft",1));
        var artifact=exports.read("actor",result.exportId());assertEquals(draft.manifest(),artifact.manifest());assertEquals(draft.contentSha256(),artifact.contentSha256());
        assertArrayEquals(f.exportFiles.get(result.exportId()),exports.bytes("actor",result.exportId()));
    }
    @Test void unreviewedOrWrongShaNeverRenders() {
        var draft=generated();assertThrows(IllegalArgumentException.class,()->exports.create("actor","export","draft",1,draft.contentSha256()));
        assertThrows(IllegalArgumentException.class,()->exports.create("actor","other","draft",1,"b".repeat(64)));assertEquals(0,renders);assertTrue(f.exports.isEmpty());
    }
    @Test void revisionDoesNotInheritOldReviewOrAllowNewStaleExport() {
        var draft=approved();var old=exports.create("actor","old","draft",1,draft.contentSha256());
        f.service.revise("actor","edit","draft",1,draft.contentSha256(),java.util.Map.of("party_name","Explicit component edit"),"reason");
        var next=f.service.read("actor","draft",2);assertThrows(IllegalArgumentException.class,()->exports.create("actor","new","draft",2,next.contentSha256()));
        assertThrows(IllegalArgumentException.class,()->exports.create("actor","stale","draft",1,draft.contentSha256()));
        assertNotNull(exports.bytes("actor",old.exportId()));assertEquals(1,renders);
    }
    @Test void corruptOriginalAndRevocationFailClosedIncludingReplay() {
        var draft=approved();var result=exports.create("actor","export","draft",1,draft.contentSha256());
        f.exportFiles.put(result.exportId(),new byte[]{0});assertThrows(IllegalStateException.class,()->exports.bytes("actor",result.exportId()));
        assertThrows(IllegalStateException.class,()->exports.create("actor","export","draft",1,draft.contentSha256()));
        f.denied=true;assertThrows(SecurityException.class,()->exports.read("actor",result.exportId()));assertEquals(1,renders);
    }
    @Test void failedOrEmptyRendererNeverCreatesSuccessReceipt() {
        var draft=approved();broken=true;assertThrows(IllegalStateException.class,()->exports.create("actor","broken","draft",1,draft.contentSha256()));
        broken=false;empty=true;assertThrows(IllegalStateException.class,()->exports.create("actor","empty","draft",1,draft.contentSha256()));
        assertTrue(f.exports.isEmpty());assertFalse(f.receipts.containsKey("broken"));assertFalse(f.receipts.containsKey("empty"));
    }
}
