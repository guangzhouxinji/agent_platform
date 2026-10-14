package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real DM8 storage I only. Existing D input is DTO data, NOT evidence of consumable upstream objects. */
class DocumentDraftStoreDm8IT {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String run=required("ENF_DRAFT_RUN_ID");
    final Path root=Path.of(required("ENF_DRAFT_TEST_ROOT"));
    final DriverManagerDataSource source=new DriverManagerDataSource(required("ENF_AUDIT_URL"),required("ENF_AUDIT_USER"),required("ENF_AUDIT_PASSWORD"));
    final JdbcTemplate db=new JdbcTemplate(source);
    Dm8DocumentDraftStore store() throws Exception {
        assertTrue(source.getUrl().startsWith("jdbc:dm://"),"Real DM8 required");
        if(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='ENF12_DOCUMENT_DRAFT_REVISION'",Integer.class)==0)
            try(var c=source.getConnection()) {ScriptUtils.executeSqlScript(c,new FileSystemResource(required("ENF_DRAFT_MIGRATION")));}
        if(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='ENF12_DOCUMENT_DRAFT_CANDIDATE'",Integer.class)==0)
            try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(required("ENF_DRAFT_WORKFLOW_MIGRATION")));}
        if(db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='ENF12_DOCUMENT_DRAFT_EXPORT'",Integer.class)==0)
            try(var c=source.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(required("ENF_DRAFT_EXPORT_MIGRATION")));}
        return new Dm8DocumentDraftStore(db,new TransactionTemplate(new DataSourceTransactionManager(source)),json,Clock.systemUTC(),root);
    }
    Draft draft(String id) throws Exception {
        var manifest=json.readTree(Files.readString(Path.of(required("ENF_DRAFT_CONTRACT_INPUT"))));
        var upstream=manifest.get("upstreamContracts");
        var template=new ObjectVersion(upstream.get("template").get("templateId").asText(),1,upstream.get("template").get("sha256").asText());
        var rule=new ObjectVersion(upstream.get("rule").get("ruleId").asText(),1,upstream.get("rule").get("sha256").asText());
        var legal=new ObjectVersion(upstream.get("legalReference").get("referenceId").asText(),1,upstream.get("legalReference").get("sha256").asText());
        var sourceInput=new ObjectVersion(manifest.get("inputId").asText(),1,sha(Files.readAllBytes(Path.of(required("ENF_DRAFT_CONTRACT_INPUT")))));
        var input=new Input("ORG-ENF-PROJECT",Type.FACT_RECORD,sourceInput,template,rule,List.of(sourceInput),List.of(legal),null,null);
        String content=manifest.get("fixtures").get("complete").get("fields").toString();
        return new Draft(id,1,input,content,sha(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sha(json.writeValueAsBytes(input)),"PENDING_REVIEW",List.of(),"CONTROLLED_STORAGE_I_NOT_BUSINESS_ACTOR",true,"key-"+id,Instant.now());
    }
    @Test void twoConnectionsReplayCasAndDbArtifactReceiptEventConsistency() throws Exception {
        var a=store();var b=store();String id="STORAGE-"+run;String key="key-"+id;Draft d=draft(id);
        var result=a.command(key,"a".repeat(64),d.author(),()->{a.append(d,0);return new Receipt(key,"GENERATE",id,1,d.contentSha256());});
        assertEquals(result,b.command(key,"a".repeat(64),d.author(),()->{throw new AssertionError("Replay wrote");}));
        assertEquals(d,b.read(id,1).orElseThrow());
        assertThrows(IllegalArgumentException.class,()->a.command(key,"b".repeat(64),d.author(),()->result));
        assertThrows(IllegalArgumentException.class,()->b.command("cas-"+run,"c".repeat(64),d.author(),()->{b.append(d,0);return new Receipt("cas-"+run,"GENERATE",id,1,d.contentSha256());}));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_revision WHERE draft_id=?",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_receipt WHERE draft_id=?",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_event WHERE draft_id=?",Integer.class,id));
    }
    @Test void failedTransactionLeavesNoDbFileEventReceiptAndCorruptOriginalFailsClosed() throws Exception {
        var a=store();String id="ROLLBACK-"+run;Draft d=draft(id);Files.createDirectories(root);
        long before;try(var stream=Files.list(root)){before=stream.count();}
        assertThrows(IllegalStateException.class,()->a.command("rollback-"+run,"d".repeat(64),d.author(),()->{a.append(d,0);throw new IllegalStateException("Injected after artifact/DB write");}));
        assertTrue(a.history(id).isEmpty());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_receipt WHERE draft_id=?",Integer.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_event WHERE draft_id=?",Integer.class,id));
        try(var stream=Files.list(root)){assertEquals(before,stream.count());}
        String saved="CORRUPT-"+run;Draft intact=draft(saved);String key="key-"+saved;
        a.command(key,"e".repeat(64),intact.author(),()->{a.append(intact,0);return new Receipt(key,"GENERATE",saved,1,intact.contentSha256());});
        String file=db.queryForObject("SELECT artifact_key FROM enf12_document_draft_revision WHERE draft_id=?",String.class,saved);
        byte[] original=Files.readAllBytes(root.resolve(file));
        try {Files.writeString(root.resolve(file),"corrupt");assertThrows(IllegalStateException.class,()->a.read(saved,1));}
        finally{Files.write(root.resolve(file),original);}
        assertEquals(intact,a.read(saved,1).orElseThrow());
    }
    @Test void immutableRevisionHistoryAndRejectedStaleAppendReconcileAcrossConnections() throws Exception {
        var a=store();var b=store();String id="REVISE-"+run;var first=draft(id);String create="create-"+run;
        a.command(create,"f".repeat(64),first.author(),()->{a.append(first,0);return new Receipt(create,"GENERATE",id,1,first.contentSha256());});
        String content="{\"technicalStorageRevision\":2}";String digest=sha(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));String edit="edit-"+run;
        var next=new Draft(id,2,first.manifest(),content,digest,first.inputManifestHash(),"PENDING_REVIEW",List.of(),first.author(),true,edit,Instant.now());
        var receipt=a.command(edit,"1".repeat(64),first.author(),()->{a.append(next,1);return new Receipt(edit,"REVISE",id,2,digest);});
        assertEquals(receipt,b.command(edit,"1".repeat(64),first.author(),()->{throw new AssertionError("Replay mutated history");}));
        assertEquals(first,b.read(id,1).orElseThrow());assertEquals(next,b.read(id,2).orElseThrow());
        assertEquals(List.of(first,next),b.history(id));
        long files;try(var stream=Files.list(root)){files=stream.count();}
        assertThrows(IllegalArgumentException.class,()->b.command("stale-"+run,"2".repeat(64),first.author(),()->{b.append(next,1);return new Receipt("stale-"+run,"REVISE",id,2,digest);}));
        try(var stream=Files.list(root)){assertEquals(files,stream.count());}
        for(String table:List.of("revision","receipt","event"))
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_"+table+" WHERE draft_id=?",Integer.class,id));
    }
    @Test void candidateOriginalReplayCorruptionAndRollbackUseRealDm8() throws Exception {
        var a=store();var b=store();String id="CANDIDATE-"+run;var base=draft(id);String create="base-"+id;
        a.command(create,"3".repeat(64),base.author(),()->{a.append(base,0);return new Receipt(create,"GENERATE",id,1,base.contentSha256());});
        String candidateId=UUID.randomUUID().toString();String key="candidate-"+run;
        var candidate=new Candidate(candidateId,id,1,base.contentSha256(),base.manifest(),base.inputManifestHash(),base.contentJson(),base.contentSha256(),List.of(),List.of(),base.author(),key,Instant.now());
        var result=a.command(key,"4".repeat(64),base.author(),()->{a.addCandidate(candidate);return new Receipt(key,"REGENERATE",id,1,base.contentSha256(),candidateId);});
        assertEquals(result,b.command(key,"4".repeat(64),base.author(),()->{throw new AssertionError("Candidate replay wrote");}));
        assertEquals(candidate,b.candidate(candidateId).orElseThrow());assertEquals(List.of(base),b.history(id));
        String file=db.queryForObject("SELECT artifact_key FROM enf12_document_draft_candidate WHERE candidate_id=?",String.class,candidateId);
        byte[] intact=Files.readAllBytes(root.resolve(file));
        try{Files.writeString(root.resolve(file),"corrupt");assertThrows(IllegalStateException.class,()->b.candidate(candidateId));}
        finally{Files.write(root.resolve(file),intact);}
        long files;try(var stream=Files.list(root)){files=stream.count();}
        String rejected=UUID.randomUUID().toString();String failureKey="failed-candidate-"+run;
        var failed=new Candidate(rejected,id,1,base.contentSha256(),base.manifest(),base.inputManifestHash(),base.contentJson(),base.contentSha256(),List.of(),List.of(),base.author(),failureKey,Instant.now());
        assertThrows(IllegalStateException.class,()->a.command(failureKey,"5".repeat(64),base.author(),()->{a.addCandidate(failed);throw new IllegalStateException("Injected after candidate artifact and row");}));
        assertTrue(b.candidate(rejected).isEmpty());try(var stream=Files.list(root)){assertEquals(files,stream.count());}
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_receipt WHERE draft_id=?",Integer.class,id));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_event WHERE draft_id=?",Integer.class,id));
    }
    @Test void exactVersionReviewsRejectUnsubmittedAndStaleResultsWithoutSideEffects() throws Exception {
        var a=store();var b=store();String id="REVIEW-"+run;var base=draft(id);String create="base-"+id;
        a.command(create,"6".repeat(64),base.author(),()->{a.append(base,0);return new Receipt(create,"GENERATE",id,1,base.contentSha256());});
        var invalid=new Review(id,1,base.contentSha256(),"APPROVED","Unsubmitted technical review",base.author(),"invalid-"+run,Instant.now());
        assertThrows(IllegalArgumentException.class,()->a.command(invalid.requestId(),"7".repeat(64),base.author(),()->{a.addReview(invalid);return new Receipt(invalid.requestId(),"REVIEW",id,1,base.contentSha256());}));
        var submitted=new Review(id,1,base.contentSha256(),"SUBMITTED","Explicit technical submission",base.author(),"submit-"+run,Instant.now());
        a.command(submitted.requestId(),"8".repeat(64),base.author(),()->{a.addReview(submitted);return new Receipt(submitted.requestId(),"SUBMIT_REVIEW",id,1,base.contentSha256());});
        var approved=new Review(id,1,base.contentSha256(),"APPROVED","Explicit technical review",base.author(),"approve-"+run,Instant.now());
        a.command(approved.requestId(),"9".repeat(64),base.author(),()->{a.addReview(approved);return new Receipt(approved.requestId(),"REVIEW",id,1,base.contentSha256());});
        assertEquals(List.of(submitted,approved),b.reviews(id,1));
        var next=new Draft(id,2,base.manifest(),base.contentJson(),base.contentSha256(),base.inputManifestHash(),"PENDING_REVIEW",List.of(),base.author(),true,"next-"+run,Instant.now());
        a.command(next.requestId(),"a".repeat(64),base.author(),()->{a.append(next,1);return new Receipt(next.requestId(),"REVISE",id,2,next.contentSha256());});
        assertTrue(b.reviews(id,2).isEmpty());
        var stale=new Review(id,1,base.contentSha256(),"SUBMITTED","Stale submission",base.author(),"stale-review-"+run,Instant.now());
        assertThrows(IllegalArgumentException.class,()->a.command(stale.requestId(),"b".repeat(64),base.author(),()->{a.addReview(stale);return new Receipt(stale.requestId(),"SUBMIT_REVIEW",id,1,base.contentSha256());}));
        for(String table:List.of("receipt","event"))assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_"+table+" WHERE draft_id=?",Integer.class,id));
        assertEquals(2,b.reviews(id,1).size());
    }
    @Test void exactReviewedExportStorageReplaysAndCorruptionMissingRollbackAndStaleFailClosed() throws Exception {
        var a=store();var b=store();String id="EXPORT-"+run;var base=draft(id);String create="base-"+id;
        a.command(create,"c".repeat(64),base.author(),()->{a.append(base,0);return new Receipt(create,"GENERATE",id,1,base.contentSha256());});
        // Original template bytes are an explicit opaque storage payload, NOT an exported case document or product-I result.
        byte[] bytes=new DocumentProfileTemplateAsset().create(DocumentProfileCatalog.builtin(json).all().getFirst());
        String exportId=UUID.randomUUID().toString(),key="export-"+run;
        var artifact=new ExportArtifact(exportId,id,1,base.manifest(),base.contentSha256(),"STORAGE_PAYLOAD_NOT_DOCUMENT_GENERATION",sha(bytes),base.author(),key,Instant.now());
        assertThrows(IllegalArgumentException.class,()->a.command(key,"d".repeat(64),base.author(),()->{a.addExport(artifact,bytes);return new Receipt(key,"EXPORT",id,1,base.contentSha256(),null,exportId);}));
        for(String outcome:List.of("SUBMITTED","APPROVED")){String reviewKey=outcome+"-"+id;var review=new Review(id,1,base.contentSha256(),outcome,"TECHNICAL_STORAGE_ONLY",base.author(),reviewKey,Instant.now());
            a.command(reviewKey,sha(reviewKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)),base.author(),()->{a.addReview(review);return new Receipt(reviewKey,outcome,id,1,base.contentSha256());});}
        var receipt=a.command(key,"d".repeat(64),base.author(),()->{a.addExport(artifact,bytes);return new Receipt(key,"EXPORT",id,1,base.contentSha256(),null,exportId);});
        assertEquals(receipt,b.command(key,"d".repeat(64),base.author(),()->{throw new AssertionError("Replay exported twice");}));assertEquals(artifact,b.export(exportId).orElseThrow());assertArrayEquals(bytes,b.exportBytes(exportId));
        String file=db.queryForObject("SELECT artifact_key FROM enf12_document_draft_export WHERE export_id=?",String.class,exportId);Path path=root.resolve(file);
        try {Files.write(path,new byte[]{0});assertThrows(IllegalStateException.class,()->b.exportBytes(exportId));Files.delete(path);assertThrows(IllegalStateException.class,()->b.exportBytes(exportId));}
        finally {Files.write(path,bytes);}
        String failedId=UUID.randomUUID().toString(),failedKey="failed-export-"+run;var failed=new ExportArtifact(failedId,id,1,base.manifest(),base.contentSha256(),artifact.rendererVersion(),sha(bytes),base.author(),failedKey,Instant.now());
        long fileCount;try(var stream=Files.list(root)){fileCount=stream.count();}
        assertThrows(IllegalStateException.class,()->a.command(failedKey,"e".repeat(64),base.author(),()->{a.addExport(failed,bytes);throw new IllegalStateException("Injected after export row and original");}));
        assertTrue(b.export(failedId).isEmpty());try(var stream=Files.list(root)){assertEquals(fileCount,stream.count());}
        var next=new Draft(id,2,base.manifest(),base.contentJson(),base.contentSha256(),base.inputManifestHash(),"PENDING_REVIEW",List.of(),base.author(),true,"next-"+id,Instant.now());
        a.command(next.requestId(),"f".repeat(64),base.author(),()->{a.append(next,1);return new Receipt(next.requestId(),"REVISE",id,2,next.contentSha256());});
        assertThrows(IllegalArgumentException.class,()->a.command("stale-export-"+run,"0".repeat(64),base.author(),()->{a.addExport(failed,bytes);return new Receipt("stale-export-"+run,"EXPORT",id,1,base.contentSha256(),null,failedId);}));
        assertArrayEquals(bytes,b.exportBytes(exportId));assertTrue(b.reviews(id,2).isEmpty());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_export WHERE draft_id=?",Integer.class,id));
        for(String table:List.of("receipt","event"))assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_"+table+" WHERE draft_id=?",Integer.class,id));
    }
    static String sha(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip permitted");return value;}
}
