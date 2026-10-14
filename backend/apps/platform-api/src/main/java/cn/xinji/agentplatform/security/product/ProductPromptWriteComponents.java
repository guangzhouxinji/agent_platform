package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Private bounded write assembly; its objects never become shared identity or READ beans. */
record ProductPromptWriteComponents(ProductPromptStore store,ProductPromptAuthorityStore authority,
        ProductPromptCreateRequestStore requests,ProductPromptWorkspaceAuthorization authorization,
        ProductPromptInputPermission input,ProductPromptBindingRegistry registry,
        DataSourceTransactionManager mainManager,DataSourceTransactionManager authorityManager,Clock clock) {
    static ProductPromptWriteComponents assemble(Environment env,JdbcTemplate db,
            PlatformTransactionManager manager,ProductGrantWatermark existingWatermark,ObjectMapper mapper){
        Core c=core(env,db,manager,existingWatermark,mapper,false);
        return new ProductPromptWriteComponents(c.store(),c.authority(),c.requests(),c.authorization(),c.input(),c.registry(),
                c.mainManager(),c.authorityManager(),c.clock());
    }
    static ProductPromptCopyApplication assembleCopy(Environment env,JdbcTemplate db,
            PlatformTransactionManager manager,ProductGrantWatermark existingWatermark,ObjectMapper mapper){
        Core c=core(env,db,manager,existingWatermark,mapper,true);
        return new ProductPromptCopyApplication(c.store(),c.authority(),c.requests(),c.authorization(),c.copyInput(),c.registry(),
                c.mainManager(),c.authorityManager(),c.clock());
    }
    private record Core(ProductPromptStore store,ProductPromptAuthorityStore authority,ProductPromptCreateRequestStore requests,
            ProductPromptWorkspaceAuthorization authorization,ProductPromptBindingRegistry registry,
            DataSourceTransactionManager mainManager,DataSourceTransactionManager authorityManager,Clock clock,
            ProductPromptInputPermission input,ProductPromptCopyInputPermission copyInput) { }
    private static Core core(Environment env,JdbcTemplate db,PlatformTransactionManager manager,
            ProductGrantWatermark existingWatermark,ObjectMapper mapper,boolean copy){
        if(!Boolean.TRUE.equals(env.getProperty("product.consultation.prompt.enabled",Boolean.class))
                ||!Boolean.TRUE.equals(env.getProperty("product.identity.enabled",Boolean.class)))
            throw new IllegalArgumentException("Prompt CREATE requires prompt and product identity");
        if(!(manager instanceof DataSourceTransactionManager mainManager)||db.getDataSource()==null
                ||mainManager.getDataSource()!=db.getDataSource()||existingWatermark.mainDataSource()!=db.getDataSource())
            throw new IllegalArgumentException("Prompt CREATE requires the exact JDBC manager and identity datasource");
        var authoritySource=existingWatermark.independentDatabase().getDataSource();
        if(authoritySource==null||authoritySource==db.getDataSource())throw new IllegalArgumentException("Prompt CREATE requires independent authority");
        var main=new ProductPromptStatementJdbcTemplate(db.getDataSource());
        var independent=new ProductPromptStatementJdbcTemplate(authoritySource);
        var authorityManager=new DataSourceTransactionManager(authoritySource);
        Clock clock=Clock.systemUTC();String prefix="product.consultation.prompt.";
        var files=new ProductSourceEvidenceFiles(Path.of(required(env,prefix+"evidence-root")),1048576);
        var registry=new ProductPromptBindingRegistry(files,required(env,prefix+"bindings-ref"),required(env,prefix+"bindings-sha256"));
        String issuer=required(env,"product.identity.issuer");
        var input=copy?null:new ProductPromptInputPermission(files,required(env,prefix+"input-permissions-ref"),
                required(env,prefix+"input-permissions-sha256"),clock,issuer);
        var copyInput=copy?new ProductPromptCopyInputPermission(files,required(env,prefix+"copy-input-permissions-ref"),
                required(env,prefix+"copy-input-permissions-sha256"),clock,issuer):null;
        schema(main,independent);
        if(copy)independent.queryForList("SELECT source_workspace_id,source_prompt_id,source_revision_id,source_revision_number,"
                +"source_record_sha,source_body_until,source_audit_until FROM idp_prompt_request WHERE 1=0");
        var store=new ProductPromptStore(main,mainManager,mapper,clock);
        var authority=new ProductPromptAuthorityStore(independent,authorityManager,clock);
        store.jdbc.requireSeparate(authority.jdbc);
        var watermark=new ProductGrantWatermark(main,independent,issuer);
        var access=new ProductConsultationAccess(main,watermark,issuer,clock);
        var keyTransactions=new TransactionTemplate(mainManager);
        keyTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);keyTransactions.setTimeout(30);
        // A private verifier uses existing key rows and the same wrapping configuration. No key lifecycle action is invoked.
        var keys=new ProductSigningKeys(main,keyTransactions,issuer,required(env,"product.identity.master-key"),
                Duration.parse(required(env,"product.identity.token-verification-window")),clock);
        var decoder=new ProductIssuerConfiguration(env).jwtDecoder(keys,main,watermark,issuer,required(env,"product.identity.audience"));
        var authorization=new ProductPromptWorkspaceAuthorization(store,authority,mainManager,registry.evidence(),watermark,access,decoder,issuer,clock);
        var requests=new ProductPromptCreateRequestStore(authority,mapper,clock);
        return new Core(store,authority,requests,authorization,registry,mainManager,authorityManager,clock,input,copyInput);
    }
    private static String required(Environment env,String key){
        String value=env.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Prompt CREATE configuration incomplete");return value;
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
                "revision_id,prompt_id,revision_number,record_sha,content_sha,submitted_at,body_until,audit_until,body_state,result_sha,key_sha,request_sha,issuer,subject_id FROM idp_revision_witness",
                "request_id,workspace_id,issuer,subject_id,operation,key_sha,prompt_id,kind,target_revision_id,expected_generation,expected_revision_id,target_generation,receipt_id,"
                        +"prepared_format,request_format,request_sha,content_sha,record_sha,result_sha,before_sha,expected_after_sha,prepared_sha,prepared_at,body_until,audit_until,"
                        +"intent_until,container_until,control_until,scope_version,registry_sha,approval_ref,approval_sha,policy_ref,policy_sha,input_permission_ref,input_permission_sha,generation_source_ref,state FROM idp_prompt_request"})
            authority.queryForList("SELECT "+query+" WHERE 1=0");
    }
}
