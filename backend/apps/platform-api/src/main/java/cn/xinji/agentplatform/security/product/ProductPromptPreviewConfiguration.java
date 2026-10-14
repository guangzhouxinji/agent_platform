package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private bounded read assembly. TEST requires no draft-write request schema or permission. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.prompt.preview-enabled",havingValue="true")
class ProductPromptPreviewConfiguration {
    @Bean ProductPromptPreviewApplication productPromptPreviewApplication(Environment env,JdbcTemplate db,
            PlatformTransactionManager manager,ProductGrantWatermark existingWatermark,ObjectMapper mapper){
        if(!Boolean.TRUE.equals(env.getProperty("product.consultation.prompt.enabled",Boolean.class))
                ||!Boolean.TRUE.equals(env.getProperty("product.identity.enabled",Boolean.class)))
            throw new IllegalArgumentException("Prompt PREVIEW requires prompt and product identity");
        if(!(manager instanceof DataSourceTransactionManager mainManager)||db.getDataSource()==null
                ||mainManager.getDataSource()!=db.getDataSource()||existingWatermark.mainDataSource()!=db.getDataSource())
            throw new IllegalArgumentException("Prompt PREVIEW requires the exact JDBC manager and identity datasource");
        var authoritySource=existingWatermark.independentDatabase().getDataSource();
        if(authoritySource==null||authoritySource==db.getDataSource())throw new IllegalArgumentException("Prompt PREVIEW requires independent authority");
        var main=new ProductPromptStatementJdbcTemplate(db.getDataSource());var independent=new ProductPromptStatementJdbcTemplate(authoritySource);
        var authorityManager=new DataSourceTransactionManager(authoritySource);Clock clock=Clock.systemUTC();String prefix="product.consultation.prompt.";
        var files=new ProductSourceEvidenceFiles(Path.of(required(env,prefix+"evidence-root")),1048576);
        var registry=new ProductPromptBindingRegistry(files,required(env,prefix+"bindings-ref"),required(env,prefix+"bindings-sha256"));
        String issuer=required(env,"product.identity.issuer");
        var permissions=new ProductPromptPreviewPermission(files,required(env,prefix+"preview-test-permissions-ref"),
                required(env,prefix+"preview-test-permissions-sha256"),clock,issuer);
        schema(main,independent);
        var store=new ProductPromptStore(main,mainManager,mapper,clock);var authority=new ProductPromptAuthorityStore(independent,authorityManager,clock);
        store.jdbc.requireSeparate(authority.jdbc);
        var watermark=new ProductGrantWatermark(main,independent,issuer);var access=new ProductConsultationAccess(main,watermark,issuer,clock);
        var keyTransactions=new TransactionTemplate(mainManager);keyTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);keyTransactions.setTimeout(30);
        var keys=new ProductSigningKeys(main,keyTransactions,issuer,required(env,"product.identity.master-key"),
                Duration.parse(required(env,"product.identity.token-verification-window")),clock);
        var decoder=new ProductIssuerConfiguration(env).jwtDecoder(keys,main,watermark,issuer,required(env,"product.identity.audience"));
        var authorization=new ProductPromptWorkspaceAuthorization(store,authority,mainManager,registry.evidence(),watermark,access,decoder,issuer,clock);
        return new ProductPromptPreviewApplication(store,authority,authorization,permissions,registry,clock);
    }
    private static String required(Environment env,String key){
        String value=env.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Prompt PREVIEW configuration incomplete");return value;
    }
    private static void schema(JdbcTemplate main,JdbcTemplate authority){
        main.queryForList("SELECT "+ProductPromptBodyClearCodec.COLUMNS+" FROM conp_prompt_cleanup_receipt WHERE 1=0");
        authority.queryForList("SELECT "+ProductPromptBodyClearCodec.REQUEST_COLUMNS+" FROM idp_prompt_cleanup_request WHERE 1=0");
        for(String query:new String[]{
                "workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until FROM conp_workspace",
                "prompt_id,workspace_id,kind,head_revision_id,generation,scalar_sha,container_until,control_until FROM conp_prompt",
                "revision_id,prompt_id,revision_number,revision_json,content_sha,record_sha,submitted_at,body_until,audit_until,body_state FROM conp_revision",
                "event_id,prompt_id,revision_id,generation,operation,issuer,subject_id,submitted_at,request_sha,result_sha,audit_until FROM conp_event",
                "receipt_id,workspace_id,issuer,subject_id,operation,key_sha,request_sha,prompt_id,revision_id,generation,content_sha,result_sha,submitted_at,audit_until FROM conp_receipt"})
            main.queryForList("SELECT "+query+" WHERE 1=0");
        for(String query:new String[]{
                "workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until,control_state,registry_intent_id,expected_registry_sha,intent_until FROM idp_workspace",
                "prompt_id,workspace_id,kind,generation,head_revision_id,scalar_sha,state,intent_id,operation,key_sha,request_sha,before_sha,expected_after_sha,target_generation,target_revision_id,intent_until,container_until,control_until,cleanup_request_id FROM idp_prompt_control",
                "revision_id,prompt_id,revision_number,record_sha,content_sha,submitted_at,body_until,audit_until,body_state,result_sha,key_sha,request_sha,issuer,subject_id FROM idp_revision_witness"})
            authority.queryForList("SELECT "+query+" WHERE 1=0");
    }
}
