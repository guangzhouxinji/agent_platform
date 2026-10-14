package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit read-only product assembly. No source-engine dependency or registration side effect. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.prompt.enabled",havingValue="true")
class ProductPromptReadConfiguration {
    @Bean ProductPromptReadApplication productPromptReadApplication(Environment env,JdbcTemplate db,
            PlatformTransactionManager manager,ProductGrantWatermark watermark,JwtDecoder decoder,ObjectMapper mapper){
        if(!Boolean.TRUE.equals(env.getProperty("product.identity.enabled",Boolean.class)))
            throw new IllegalArgumentException("Prompt reads require product identity");
        if(!(manager instanceof DataSourceTransactionManager mainManager))
            throw new IllegalArgumentException("Prompt reads require the exact JDBC manager");
        Clock clock=Clock.systemUTC();String prefix="product.consultation.prompt.";
        var files=new ProductSourceEvidenceFiles(Path.of(required(env,prefix+"evidence-root")),1048576);
        var registry=new ProductPromptBindingRegistry(files,required(env,prefix+"bindings-ref"),required(env,prefix+"bindings-sha256"));
        JdbcTemplate independent=watermark.independentDatabase();
        schema(db,independent);
        var store=new ProductPromptStore(db,mainManager,mapper,clock);
        var authority=new ProductPromptAuthorityStore(independent,new DataSourceTransactionManager(independent.getDataSource()),clock);
        String issuer=required(env,"product.identity.issuer");
        var access=new ProductConsultationAccess(db,watermark,issuer,clock);
        var authorization=new ProductPromptWorkspaceAuthorization(store,authority,mainManager,registry.evidence(),watermark,access,decoder,issuer,clock);
        return new ProductPromptReadApplication(store,authority,authorization,registry,mainManager,clock);
    }
    private static String required(Environment env,String key){
        String value=env.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Prompt configuration incomplete");return value;
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
