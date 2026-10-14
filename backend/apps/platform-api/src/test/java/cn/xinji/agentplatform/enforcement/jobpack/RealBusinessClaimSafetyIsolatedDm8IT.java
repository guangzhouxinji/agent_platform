package cn.xinji.agentplatform.enforcement.jobpack;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8RealBusinessCollectionTaskStore;
import cn.xinji.agentplatform.enforcement.adapter.out.dm8.LocalMaterialFiles;
import cn.xinji.agentplatform.enforcement.testing.EnforcementDm8IsolationGuard;
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

/** Raw storage/importer mechanism I only; no product auth, person approval, job-pack or integration run. */
class RealBusinessClaimSafetyIsolatedDm8IT {
    @Test void claimedTaskReentryPreservesAssignmentButNewKeyAddsClaimEvent() throws Exception {
        var scope=EnforcementDm8IsolationGuard.beforeConnect(required("JOB_PACK_DM8_URL"),required("JOB_PACK_DM8_USER"));
        var source=new DriverManagerDataSource(required("JOB_PACK_DM8_URL"),required("JOB_PACK_DM8_USER"),required("JOB_PACK_DM8_PASSWORD"));
        var db=new JdbcTemplate(source);scope.assertFreshSchema(db);
        for(String migration:List.of("V062__enforcement_v2_job_pack.sql","V066__enforcement_real_business_collection.sql"))
            try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of(required("ENF_TEST_MIGRATION_ROOT"),migration)));}
        var json=new ObjectMapper().findAndRegisterModules();
        var tasks=new Dm8RealBusinessCollectionTaskStore(db,new TransactionTemplate(new DataSourceTransactionManager(source)),json,Clock.systemUTC());
        var files=new LocalMaterialFiles(scope.fileRoot());
        var importer=new PublicCollectionTaskImporter(tasks,files,json,"data/enforcement/public-job-pack-candidates-v1/candidates.json");
        var args=new org.springframework.boot.DefaultApplicationArguments(new String[0]);importer.run(args);
        assertEquals(12,tasks.list().size());String id="ENF-RBC-CNIPA-GUIDANCE-CASE-1";
        var original=tasks.find(id).orElseThrow();assertEquals("UNCLAIMED",original.assignmentState());
        var beforeFiles=hashes(scope.fileRoot());
        String actor="CONTROLLED_CLAIM_STORAGE_NOT_BUSINESS_ACTOR",org="ISOLATED_ORG";
        var first=tasks.claim(id,actor,org,"first-"+scope.runId(),"a".repeat(64));
        assertEquals("CLAIMED",first.assignmentState());assertEquals(2,first.version());assertNotNull(first.claimedAt());
        assertEquals(first,tasks.claim(id,actor,org,"first-"+scope.runId(),"a".repeat(64)));
        assertEquals(1,events(db,id));
        var repeated=tasks.claim(id,actor,org,"new-key-"+scope.runId(),"b".repeat(64));
        assertEquals(first,repeated);assertEquals(2,events(db,id)); // Risk observation, NOT a newly approved product behavior.
        assertEquals(repeated,tasks.claim(id,actor,org,"new-key-"+scope.runId(),"b".repeat(64)));assertEquals(2,events(db,id));
        assertThrows(IllegalArgumentException.class,()->tasks.claim(id,"OTHER_CONTROLLED_ACTOR",org,"other-"+scope.runId(),"c".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->tasks.claim(id,actor,"OTHER_ISOLATED_ORG","other-org-"+scope.runId(),"d".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->tasks.claim(id,actor,org,"first-"+scope.runId(),"e".repeat(64)));
        assertEquals(2,events(db,id));assertEquals(first,tasks.find(id).orElseThrow());
        assertEquals(beforeFiles,hashes(scope.fileRoot()));
        var beforeTasks=tasks.list();importer.run(args);assertEquals(beforeTasks,tasks.list());assertEquals(beforeFiles,hashes(scope.fileRoot()));
        assertEquals(2,events(db,id));
        var originals=new ArrayList<Map<String,Object>>();
        for(var task:tasks.list()){
            var publicInput=task.publicInput();byte[] bytes=files.read(publicInput.rawFileKey());String sha=sha(bytes);
            assertEquals(publicInput.rawSha256(),sha);
            originals.add(Map.of("taskId",task.taskId(),"sourceUrl",publicInput.sourceUrl(),"fileKey",publicInput.rawFileKey(),"sha256",sha));
        }
        for(String table:List.of("enf8_job_pack_revision","enf8_job_pack_receipt","enf8_job_pack_event","enf8_integration_trigger"))
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        var evidence=new LinkedHashMap<String,Object>();evidence.put("runId",scope.runId());evidence.put("finishedAt",Instant.now().toString());
        evidence.put("scope","ISOLATED_CLAIM_STORAGE_IMPORTER_ONLY_NOT_PRODUCT_AUTH_HUMAN_OR_RESTART_APPROVAL");
        evidence.put("tasks",12);evidence.put("claimEvents",2);evidence.put("claimedVersion",first.version());evidence.put("claimedAt",first.claimedAt().toString());
        evidence.put("sameKeyNoNewEvent",true);evidence.put("sameActorNewKeyAddsEventWithoutNewAssignmentVersion",true);evidence.put("otherActorOrganizationAndFingerprintDenied",true);
        evidence.put("importerReentryTaskAndOriginalsUnchanged",true);evidence.put("originals",originals);evidence.put("jobPackWrites",0);evidence.put("integrationTriggers",0);evidence.put("humanApprovalWrites",0);evidence.put("mainConnections",0);
        Files.writeString(Path.of(required("ENF_TEST_EVIDENCE"),"claim-reconciliation.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),StandardOpenOption.CREATE_NEW);
    }
    static int events(JdbcTemplate db,String id){return db.queryForObject("SELECT COUNT(*) FROM enf8_real_business_task_event WHERE task_id=?",Integer.class,id);}
    static Map<String,String> hashes(Path root)throws Exception {var out=new TreeMap<String,String>();try(var paths=Files.list(root)){for(var file:paths.toList())if(Files.isRegularFile(file))out.put(file.getFileName().toString(),sha(Files.readAllBytes(file)));}return out;}
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}

