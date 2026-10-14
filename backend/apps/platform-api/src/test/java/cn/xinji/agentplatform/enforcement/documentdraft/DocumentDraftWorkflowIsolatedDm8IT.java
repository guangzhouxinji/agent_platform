package cn.xinji.agentplatform.enforcement.documentdraft;

import static cn.xinji.agentplatform.enforcement.documentdraft.DocumentDraftContract.*;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import cn.xinji.agentplatform.enforcement.testing.EnforcementDm8IsolationGuard;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real isolated DM8 workflow/transaction I. Controlled Inputs/Access are NOT product or human evidence. */
class DocumentDraftWorkflowIsolatedDm8IT {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final Clock clock=Clock.systemUTC();
    final String actor="CONTROLLED_WORKFLOW_I_NOT_BUSINESS_ACTOR";
    @Test void composedRevisionCandidateAdoptionAndRollbackPreserveHistory() throws Exception {
        var scope=EnforcementDm8IsolationGuard.beforeConnect(required("JOB_PACK_DM8_URL"),required("JOB_PACK_DM8_USER")); // Before constructing even the first datasource.
        var source=new DriverManagerDataSource(required("JOB_PACK_DM8_URL"),required("JOB_PACK_DM8_USER"),required("JOB_PACK_DM8_PASSWORD"));
        var db=new JdbcTemplate(source);scope.assertFreshSchema(db);
        for(String migration:List.of("V083__enforcement_document_draft.sql","V085__enforcement_document_draft_workflow.sql","V087__enforcement_document_draft_export.sql"))
            try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of(required("ENF_TEST_MIGRATION_ROOT"),migration)));}
        var store=new Dm8DocumentDraftStore(db,new TransactionTemplate(new DataSourceTransactionManager(source)),json,clock,scope.fileRoot());
        var independent=new Dm8DocumentDraftStore(new JdbcTemplate(new DriverManagerDataSource(required("JOB_PACK_DM8_URL"),required("JOB_PACK_DM8_USER"),required("JOB_PACK_DM8_PASSWORD"))),new TransactionTemplate(new DataSourceTransactionManager(source)),json,clock,scope.fileRoot());
        var ports=new ControlledPorts();var service=new DocumentDraftService(ports,ports,store,clock);
        var workflow=new DocumentDraftWorkflow(service,ports,ports,store,clock,json);
        var v=new ObjectVersion("ISOLATED_MECHANICAL_INPUT",1,"a".repeat(64));
        var input=new Input("ISOLATED_ORG",Type.FACT_RECORD,v,v,v,List.of(v),List.of(v),null,null);
        String id="FACT-"+scope.runId();
        var prepared=service.prepare(actor,id,input);assertTrue(prepared.ready());
        var generated=service.generate(actor,"generate-"+id,id,input,prepared.inputManifestHash());
        assertEquals(generated,service.generate(actor,"generate-"+id,id,input,prepared.inputManifestHash()));
        var original=independent.read(id,1).orElseThrow();
        var revised=service.revise(actor,"revise-"+id,id,1,original.contentSha256(),Map.of("party_name","CONTROLLED TEXT REVISION"),"Isolation mechanism test only");
        assertEquals(revised,service.revise(actor,"revise-"+id,id,1,original.contentSha256(),Map.of("party_name","CONTROLLED TEXT REVISION"),"Isolation mechanism test only"));
        var current=independent.read(id,2).orElseThrow();ports.text="CONTROLLED REGENERATION VALUE";
        var candidate=workflow.regenerate(actor,"candidate-"+id,id,2,current.contentSha256(),input,service.prepare(actor,id,input).inputManifestHash());
        assertEquals(current,independent.history(id).getLast());
        assertEquals(1,workflow.candidates(actor,id,2).size());
        assertTrue(workflow.candidates(actor,id,2).getFirst().differences().stream().anyMatch(d->d.field().equals("party_name")));
        var adopted=workflow.adopt(actor,"adopt-"+id,id,2,current.contentSha256(),candidate.candidateId());
        assertEquals(adopted,workflow.adopt(actor,"adopt-"+id,id,2,current.contentSha256(),candidate.candidateId()));
        var head=independent.read(id,3).orElseThrow();assertEquals(original,independent.read(id,1).orElseThrow());
        assertEquals(current,independent.read(id,2).orElseThrow());assertEquals(input,head.manifest());
        assertTrue(head.contentJson().contains(ports.text));assertEquals("PENDING_REVIEW",head.reviewState());
        ports.changed=true;
        assertThrows(IllegalStateException.class,()->workflow.adopt(actor,"adopt-"+id,id,2,current.contentSha256(),candidate.candidateId()));ports.changed=false;
        ports.denied=true;
        assertThrows(SecurityException.class,()->workflow.adopt(actor,"adopt-"+id,id,2,current.contentSha256(),candidate.candidateId()));ports.denied=false;
        assertThrows(IllegalArgumentException.class,()->service.revise(actor,"stale-"+id,id,2,current.contentSha256(),Map.of("party_name","STALE"),"stale"));
        var before=files(scope.fileRoot());
        var failed=new Draft(id,4,head.manifest(),head.contentJson(),head.contentSha256(),head.inputManifestHash(),head.reviewState(),head.missingItems(),actor,true,"rollback-"+id,clock.instant());
        assertThrows(IllegalStateException.class,()->store.command(failed.requestId(),"b".repeat(64),actor,()->{store.append(failed,3);throw new IllegalStateException("Controlled rollback after new own artifact");}));
        assertEquals(before,files(scope.fileRoot()));assertTrue(independent.read(id,4).isEmpty());
        assertEquals(3,independent.history(id).size());
        for(String table:List.of("receipt","event"))assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_"+table+" WHERE draft_id=?",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_candidate WHERE draft_id=?",Integer.class,id));
        var failedCandidate=new Candidate(UUID.randomUUID().toString(),id,3,head.contentSha256(),head.manifest(),head.inputManifestHash(),head.contentJson(),head.contentSha256(),head.missingItems(),List.of(),actor,"rollback-candidate-"+id,clock.instant());
        var candidateBefore=files(scope.fileRoot());
        assertThrows(IllegalStateException.class,()->store.command(failedCandidate.requestId(),"c".repeat(64),actor,()->{store.addCandidate(failedCandidate);throw new IllegalStateException("Controlled rollback after candidate original and row");}));
        assertTrue(independent.candidate(failedCandidate.id()).isEmpty());assertEquals(candidateBefore,files(scope.fileRoot()));
        for(String table:List.of("receipt","event"))assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_"+table+" WHERE draft_id=?",Integer.class,id));
        var retainedBefore=files(scope.fileRoot());
        String draftKey=db.queryForObject("SELECT artifact_key FROM enf12_document_draft_revision WHERE draft_id=? AND revision=3",String.class,id);
        String candidateKey=db.queryForObject("SELECT artifact_key FROM enf12_document_draft_candidate WHERE candidate_id=?",String.class,candidate.candidateId());
        // These exact files were created above in this fresh owned schema/root, never runtime history.
        var draftFile=scope.fileRoot().resolve(draftKey);var candidateFile=scope.fileRoot().resolve(candidateKey);
        byte[] draftBytes=Files.readAllBytes(draftFile),candidateBytes=Files.readAllBytes(candidateFile);
        try {Files.write(draftFile,new byte[]{0});assertThrows(IllegalStateException.class,()->service.read(actor,id,3));}
        finally {Files.write(draftFile,draftBytes);}
        try {Files.write(candidateFile,new byte[]{0});assertThrows(IllegalStateException.class,()->workflow.candidates(actor,id,2));}
        finally {Files.write(candidateFile,candidateBytes);}
        Path movedDraft=scope.fileRoot().resolve(draftKey+".owned-isolation-quarantine");
        Files.move(draftFile,movedDraft);
        try {assertThrows(IllegalStateException.class,()->service.read(actor,id,3));}
        finally {Files.move(movedDraft,draftFile);}
        Path movedCandidate=scope.fileRoot().resolve(candidateKey+".owned-isolation-quarantine");
        Files.move(candidateFile,movedCandidate);
        try {assertThrows(IllegalStateException.class,()->workflow.candidates(actor,id,2));}
        finally {Files.move(movedCandidate,candidateFile);}
        assertEquals(retainedBefore,files(scope.fileRoot()));assertEquals(head,independent.read(id,3).orElseThrow());
        assertEquals(candidate.candidateId(),independent.candidate(candidate.candidateId()).orElseThrow().id());
        ports.missing=true;String missingId="MISSING-"+scope.runId();
        var incomplete=service.generate(actor,"generate-"+missingId,missingId,input,service.prepare(actor,missingId,input).inputManifestHash());
        var missing=independent.read(missingId,incomplete.revision()).orElseThrow();assertEquals("INCOMPLETE_DRAFT",missing.reviewState());
        var missingBefore=files(scope.fileRoot());
        assertThrows(IllegalArgumentException.class,()->workflow.submit(actor,"submit-"+missingId,missingId,1,missing.contentSha256()));
        assertThrows(IllegalArgumentException.class,()->workflow.review(actor,"review-"+missingId,missingId,1,missing.contentSha256(),"APPROVED","Must never execute human review"));
        var export=new DocumentDraftExportService(service,ports,ports,store,new ExportRenderer(){public String version(){return "NEVER_CALLED";}public byte[] render(String a,Draft d){throw new AssertionError("Incomplete draft rendered");}},clock);
        assertThrows(IllegalArgumentException.class,()->export.create(actor,"export-"+missingId,missingId,1,missing.contentSha256()));
        assertEquals(missingBefore,files(scope.fileRoot()));assertTrue(independent.reviews(missingId,1).isEmpty());
        ports.missing=false;String noLegalId="NOLEGAL-"+scope.runId();
        var noLegalInput=new Input(input.organization(),input.type(),input.caseInput(),input.template(),input.rule(),input.evidence(),List.of(),null,null);
        var noLegalPreparation=service.prepare(actor,noLegalId,noLegalInput);assertFalse(noLegalPreparation.ready());
        var noLegalReceipt=service.generate(actor,"generate-"+noLegalId,noLegalId,noLegalInput,noLegalPreparation.inputManifestHash());
        var noLegal=independent.read(noLegalId,noLegalReceipt.revision()).orElseThrow();
        assertEquals("INCOMPLETE_DRAFT",noLegal.reviewState());assertTrue(noLegal.missingItems().stream().anyMatch(d->d.field().equals("legal_references")&&d.blocking()));
        var noLegalBefore=files(scope.fileRoot());
        assertThrows(IllegalArgumentException.class,()->workflow.submit(actor,"submit-"+noLegalId,noLegalId,1,noLegal.contentSha256()));
        assertThrows(IllegalArgumentException.class,()->workflow.review(actor,"review-"+noLegalId,noLegalId,1,noLegal.contentSha256(),"APPROVED","Must reject missing legal reference"));
        assertThrows(IllegalArgumentException.class,()->export.create(actor,"export-"+noLegalId,noLegalId,1,noLegal.contentSha256()));
        assertEquals(noLegalBefore,files(scope.fileRoot()));assertTrue(independent.reviews(noLegalId,1).isEmpty());
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_revision",Integer.class));
        for(String table:List.of("receipt","event"))assertEquals(6,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_"+table,Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_candidate",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_review",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM enf12_document_draft_export",Integer.class));
        var checks=new ArrayList<Map<String,Object>>();
        for(String table:List.of("revision","candidate"))db.query("SELECT artifact_key,artifact_sha256 FROM enf12_document_draft_"+table,rs->{
            try {var file=scope.fileRoot().resolve(rs.getString(1));String actual=sha(Files.readAllBytes(file));assertEquals(rs.getString(2),actual);checks.add(Map.of("table",table,"file",rs.getString(1),"sha256",actual));}
            catch(Exception failure){throw new IllegalStateException(failure);}
        });
        assertEquals(6,checks.size());
        var evidence=new LinkedHashMap<String,Object>();evidence.put("runId",scope.runId());evidence.put("finishedAt",Instant.now().toString());
        evidence.put("scope","ISOLATED_WORKFLOW_TRANSACTION_I_NOT_PRODUCT_IDENTITY_BROWSER_OR_HUMAN");
        evidence.put("draftVersions",5);evidence.put("candidates",1);evidence.put("events",6);evidence.put("receipts",6);evidence.put("originals",checks);
        evidence.put("candidateRollbackPreservedPriorFiles",true);evidence.put("draftAndCandidateMissingAndCorruptFailClosed",true);evidence.put("missingLegalReviewExportDenied",true);
        evidence.put("rollbackPreservedPriorFiles",true);evidence.put("revocationDependencyCasDenied",true);evidence.put("missingReviewExportDenied",true);
        evidence.put("mainConnections",0);evidence.put("humanReviewWrites",0);
        Files.writeString(Path.of(required("ENF_TEST_EVIDENCE"),"workflow-reconciliation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),StandardOpenOption.CREATE_NEW);
    }
    static Map<String,String> files(Path root)throws Exception {
        var map=new TreeMap<String,String>();try(var paths=Files.list(root)){for(var file:paths.toList())if(Files.isRegularFile(file))map.put(file.getFileName().toString(),sha(Files.readAllBytes(file)));}return map;
    }
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
    static final class ControlledPorts implements DocumentDraftContract.Inputs,Access {
        String text="CONTROLLED ISOLATED SOURCE NOT CASE FACT";boolean missing,changed,denied;
        public void require(String actor,String org,String id,long version,String action){if(denied)throw new SecurityException("Controlled revocation");}
        public void assertCurrent(String actor,Input input){require(actor,input.organization(),"",1,"CURRENT");if(changed)throw new IllegalStateException("Controlled dependency change");}
        public void assertReadable(String actor,Input input){require(actor,input.organization(),"",1,"READ");}
        public Resolved resolve(String actor,Input input){
            var mapping=new Mapping("party_name","party.name","1",Mode.DIRECT,null,Missing.BLOCK);
            var fields=missing?List.<Mapped>of():List.of(new Mapped("party_name",text,"controlled", "1","controlled locator",Mode.DIRECT,false));
            var diagnostics=missing?List.of(new cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Diagnostic("party_name","party.name","MISSING_SOURCE_VALUE",true)):List.<cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.Diagnostic>of();
            var profile=new DocumentTemplateProfile("ISOLATED_PROFILE",1,"NON_OFFICIAL",Type.FACT_RECORD,"Controlled test profile",Instant.EPOCH,null,List.of("*"),List.of("*"),
                List.of(new DocumentTemplateProfile.FieldDefinition("party_name","Controlled text","LONG_TEXT",true,false,true)),List.of(mapping),
                List.of(new DocumentTemplateProfile.Section("facts","Controlled",new DocumentTemplateProfile.Condition(DocumentTemplateProfile.Operator.ALWAYS,null,null),List.of("party_name"))));
            var rule=new Rule(input.rule().id(),1,input.organization(),State.PUBLISHED,input.template().id(),1,input.template().sha256(),"CONTROLLED_SCHEMA","1",List.of(mapping));
            var sources=missing?Map.<String,DocumentProfileEngine.SourceDatum>of():Map.of("party.name",new DocumentProfileEngine.SourceDatum(text,"controlled",1,"a".repeat(64),"controlled locator",true));
            return new Resolved(input,true,true,true,true,false,false,new Trial(!missing,false,fields,diagnostics),profile,rule,null,null,sources);
        }
    }
}
