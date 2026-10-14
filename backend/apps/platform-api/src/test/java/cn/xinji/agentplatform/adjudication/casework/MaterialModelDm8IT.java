package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.materials.*;
import cn.xinji.agentplatform.adjudication.materials.adapter.DeepSeekMaterialOrganizationAdapter;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Approved component input, not a business identity/HTTP or real-case value validation. Budget schema persists. */
@EnabledIfEnvironmentVariable(named="ADJ_MODEL_COMPONENT_LIVE",matches="true")
class MaterialModelDm8IT {
    @Test void approvedActualModelAndPersistentDm8EncryptedRecovery()throws Exception {
        Path evidence=Path.of(required("ADJ_MODEL_COMPONENT_EVIDENCE"));Files.createDirectories(evidence);
        var json=new ObjectMapper().findAndRegisterModules();Path root=Path.of("docs/evidence/adjudication-llm-materials-20261003");
        var base=json.readTree(Files.readString(root.resolve("user-approval-v1.json")));
        var supplier=json.readTree(Files.readString(root.resolve("supplier-approval-v1.json")));
        assertEquals("ADJ-LLM-SUPPLIER-USER-20261003-01",supplier.path("reference").asText());
        var manifest=json.readTree(Files.readString(root.resolve("user-approved-material-package-v1/manifest.json")));
        var inputs=new ArrayList<MaterialOrganizationModel.Input>();var hashes=new HashSet<String>();
        for(var f:manifest.path("files")) {
            String name=f.path("path").asText();assertTrue(name.matches("[a-z-]+-v1\\.txt"));String text=Files.readString(root.resolve("user-approved-material-package-v1").resolve(name));
            String sha=sha256(text);assertEquals(f.path("sha256").asText(),sha);hashes.add(sha);
            inputs.add(new MaterialOrganizationModel.Input("s"+(inputs.size()+1),MaterialOrganizationModel.Side.valueOf(f.path("providerSide").asText()),new Source("material-"+(inputs.size()+1),"v1","text:"+name,sha,InputKind.PROJECT_AUTHORED),text));
        }
        var policy=new MaterialModelPolicy(base.path("reference").asText(),sha256(Files.readString(root.resolve("user-approval-v1.json"))),sha256(Files.readString(root.resolve("supplier-approval-v1.json"))),
                "adjudication-materials-case-01",Instant.parse("2026-11-01T16:00:00Z"),hashes,"deepseek-flash");
        String url=required("ADJ_MODEL_COMPONENT_DB_URL"),user=required("ADJ_MODEL_COMPONENT_DB_USER"),password=required("ADJ_MODEL_COMPONENT_DB_PASSWORD");
        if(!"ADJLLM20261003".equals(user) || !password.matches("[A-Za-z0-9]{32}"))throw new IllegalStateException("EXACT_MODEL_SCHEMA_REQUIRED");
        try(var admin=DriverManager.getConnection(url,required("ADJ_DM8_ADMIN_USER"),required("ADJ_DM8_ADMIN_PASSWORD"))) {
            var check=admin.prepareStatement("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?");check.setString(1,user);
            try(var r=check.executeQuery()){r.next();if(r.getInt(1)==0)try(var command=admin.createStatement()){command.execute("CREATE USER "+user+" IDENTIFIED BY \""+password+"\"");command.execute("GRANT RESOURCE TO "+user);}}
        }
        var ds=new DriverManagerDataSource(url,user,password);var db=new JdbcTemplate(ds);
        int tables=db.queryForObject("SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME IN ('ADJ3_MODEL_BUDGET','ADJ3_MATERIAL_RUN')",Integer.class);
        if(tables==0)try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V090__adjudication_material_model_run.sql"));}
        else assertEquals(2,tables,"Never recreate/drop a partially initialized budget schema");
        ensureApprovalBinding(ds);Clock clock=Clock.systemUTC();Path keys=Path.of(required("ADJ_MODEL_COMPONENT_KEY_DIRECTORY"));var vault=new MaterialRunVault(keys,clock);
        var store=new JdbcMaterialRunStore(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),vault,clock);store.purgeExpired();store.initialize(policy);
        policy.require(policy.caseId(),inputs,clock);var model=new DeepSeekMaterialOrganizationAdapter(required("DEEPSEEK_API_KEY"));
        String request=model.request(inputs,2048);String requestKey="approved-component-"+UUID.randomUUID();
        long before=store.totals(policy).calls();var reservation=store.reserve(policy,requestKey,"project-owner-approved-component-validation",sha256(request),request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+4096L,2048);
        MaterialOrganizationModel.Reply reply;
        try{reply=model.invoke(request);}catch(RuntimeException failed){store.uncertain(reservation);throw failed;}
        List<MaterialOrganizationModel.Candidate> candidates;
        try{candidates=new MaterialCandidateValidator().validate(inputs,reply.rawContent());}
        catch(RuntimeException invalid){store.complete(policy,reservation,reply.usage().inputTokens(),reply.usage().outputTokens(),json.writeValueAsString(Map.of("request",request,"reply",reply)),false);throw invalid;}
        String payload=json.writeValueAsString(Map.of("request",request,"reply",reply,"inputs",inputs,"candidates",candidates,"inputNature","PROJECT_AUTHORED_SUBSTITUTE_COMPONENT_ONLY"));
        store.complete(policy,reservation,reply.usage().inputTokens(),reply.usage().outputTokens(),payload,true);
        var restart=new JdbcMaterialRunStore(new JdbcTemplate(new DriverManagerDataSource(url,user,password)),new TransactionTemplate(new DataSourceTransactionManager(ds)),new MaterialRunVault(keys,clock),clock);
        restart.initialize(policy);assertTrue(payload.equals(restart.load(policy,reservation.id())),"DM8 reload must preserve encrypted raw request/output/candidates exactly");
        assertEquals(before+1,restart.totals(policy).calls());assertTrue(store.reserve(policy,requestKey,"project-owner-approved-component-validation",sha256(request),request.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+4096L,2048).replay());
        String cipher=db.queryForObject("SELECT payload FROM adj3_material_run WHERE run_id=?",String.class,reservation.id());assertFalse(cipher.contains("申请方"));
        var summary=new LinkedHashMap<String,Object>();summary.put("completedAt",Instant.now().toString());summary.put("scope","APPROVED_REAL_MODEL_DM8_COMPONENT_NOT_HTTP_IDENTITY_OR_REAL_CASE_VALUE");
        summary.put("database","DM8");summary.put("schema",user);summary.put("schemaRetainedForNonResettableBudget",true);summary.put("model",reply.model());summary.put("candidateCount",candidates.size());
        summary.put("usage",reply.usage());summary.put("cumulativeBudget",restart.totals(policy));summary.put("rawAndBindingReloadEqual",true);summary.put("runId",reservation.id());summary.put("expiresAt",reservation.expiresAt().toString());
        summary.put("generatedContentInEvidence",false);summary.put("noBusinessIdentityOrGrantClaim",true);summary.put("payloadCipherSha256",sha256(cipher));
        Files.writeString(evidence.resolve("real-model-dm8-summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }
    @Test void restorePreviousRunWithCurrentCodeAndNoProviderRequest()throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();Path root=Path.of("docs/evidence/adjudication-llm-materials-20261003");
        var prior=json.readTree(Files.readString(root.resolve("real-model-dm8-01/real-model-dm8-summary.json")));
        var approved=json.readTree(Files.readString(root.resolve("user-approved-material-package-v1/manifest.json")));var hashes=new HashSet<String>();approved.path("files").forEach(f->hashes.add(f.path("sha256").asText()));
        var base=json.readTree(Files.readString(root.resolve("user-approval-v1.json")));
        var p=new MaterialModelPolicy(base.path("reference").asText(),sha256(Files.readString(root.resolve("user-approval-v1.json"))),sha256(Files.readString(root.resolve("supplier-approval-v1.json"))),"adjudication-materials-case-01",Instant.parse("2026-11-01T16:00:00Z"),hashes,"deepseek-flash");
        String user=required("ADJ_MODEL_COMPONENT_DB_USER");assertEquals("ADJLLM20261003",user);
        var ds=new DriverManagerDataSource(required("ADJ_MODEL_COMPONENT_DB_URL"),user,required("ADJ_MODEL_COMPONENT_DB_PASSWORD"));var db=new JdbcTemplate(ds);ensureApprovalBinding(ds);Clock clock=Clock.systemUTC();
        var store=new JdbcMaterialRunStore(db,new TransactionTemplate(new DataSourceTransactionManager(ds)),new MaterialRunVault(Path.of(required("ADJ_MODEL_COMPONENT_KEY_DIRECTORY")),clock),clock);store.purgeExpired();store.initialize(p);
        var before=store.totals(p);String payload=store.load(p,prior.path("runId").asText());var node=json.readTree(payload);
        var input=json.readerForListOf(MaterialOrganizationModel.Input.class).<List<MaterialOrganizationModel.Input>>readValue(node.path("inputs"));
        var checked=new MaterialCandidateValidator().validate(input,node.path("reply").path("rawContent").asText());assertEquals(prior.path("candidateCount").asInt(),checked.size());
        assertEquals(node.path("candidates"),json.valueToTree(checked));assertEquals(before,store.totals(p));
        var result=Map.of("scope","CURRENT_CODE_DM8_RELOAD_NO_PROVIDER_REQUEST","passed",true,"candidates",checked.size(),"budgetUnchanged",true,"cumulativeBudget",store.totals(p));
        Files.writeString(Path.of(required("ADJ_MODEL_COMPONENT_EVIDENCE")).resolve("current-code-reload-summary.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    private static void ensureApprovalBinding(DriverManagerDataSource ds)throws Exception {
        var db=new JdbcTemplate(ds);int columns=db.queryForObject("SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME='ADJ3_MODEL_BUDGET' AND COLUMN_NAME='APPROVAL_KEY'",Integer.class);
        if(columns==0)try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("db/dm8/V091__adjudication_model_approval_binding.sql"));}
    }
    private static String required(String name){var v=System.getenv(name);if(v==null||v.isBlank())throw new IllegalStateException("Required private deployment setting: "+name);return v;}
}
