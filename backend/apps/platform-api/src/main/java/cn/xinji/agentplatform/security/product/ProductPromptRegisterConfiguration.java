package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit REGISTER activation on the existing identity pools; startup never registers a workspace. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="product.consultation.prompt.register-enabled",havingValue="true")
class ProductPromptRegisterConfiguration {
    private static final String PREFIX="product.consultation.prompt.";

    @Bean(destroyMethod="close")
    ProductPromptRegistrationLease productPromptRegistrationLease(Environment env,JdbcTemplate db,
            PlatformTransactionManager manager,ProductGrantWatermark existingWatermark) {
        if(!Boolean.TRUE.equals(env.getProperty(PREFIX+"enabled",Boolean.class))
                ||!Boolean.TRUE.equals(env.getProperty("product.identity.enabled",Boolean.class)))
            throw invalid();
        if(!(manager instanceof DataSourceTransactionManager mainManager)
                ||!(db.getDataSource() instanceof HikariDataSource main)
                ||mainManager.getDataSource()!=main||existingWatermark.mainDataSource()!=main
                ||!(existingWatermark.independentDatabase().getDataSource() instanceof HikariDataSource authority)
                ||authority==main)throw invalid();
        // Pool admission must precede every schema query and Store constructor (which borrows metadata).
        return new ProductPromptRegistrationLease(main,authority,mainManager,
                new DataSourceTransactionManager(authority),Clock.systemUTC());
    }

    @Bean
    ProductPromptRegisterApplication productPromptRegisterApplication(Environment env,
            ProductPromptRegistrationLease lease,ObjectMapper mapper) {
        try {
            var main=new ProductPromptStatementJdbcTemplate(lease.mainSource);
            var authority=new ProductPromptStatementJdbcTemplate(lease.authoritySource);
            var files=new ProductSourceEvidenceFiles(Path.of(required(env,"evidence-root")),1048576);
            var registry=new ProductPromptBindingRegistry(files,required(env,"bindings-ref"),required(env,"bindings-sha256"));
            var approval=new ProductPromptRegisterApproval(files,registry,pins(required(env,"register-approval-pins-json")),lease.clock);
            schema(main,authority);
            var store=new ProductPromptStore(main,lease.mainManager,mapper,lease.clock);
            var control=new ProductPromptAuthorityStore(authority,lease.authorityManager,lease.clock);
            var requests=new ProductPromptRegisterStore(store,control,mapper,lease.clock);
            return new ProductPromptRegisterApplication(approval,lease,requests,
                    new ProductPromptRegisterReconciliation(requests,lease.clock));
        }catch(RuntimeException|Error failed){
            // This instance owns only its executor. Shared identity pools remain open, including on startup failure.
            lease.close();throw failed;
        }
    }

    static List<ProductPromptRegisterApproval.Pin> pins(String raw) {
        if(raw==null||raw.length()>65536||raw.getBytes(StandardCharsets.UTF_8).length>65536)throw invalid();
        try {
            var mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var value=mapper.readTree(raw);
            if(value==null||!value.isArray()||value.isEmpty()||value.size()>128)throw invalid();
            var result=new ArrayList<ProductPromptRegisterApproval.Pin>();
            var workspaces=new HashSet<String>();var references=new HashSet<String>();var digests=new HashSet<String>();
            Set<String> fields=Set.of("workspaceId","approvalRef","approvalSha");
            for(var entry:value) {
                if(!entry.isObject()||entry.size()!=fields.size())throw invalid();
                var names=entry.fieldNames();while(names.hasNext())if(!fields.contains(names.next()))throw invalid();
                for(String field:fields)if(!entry.has(field)||!entry.get(field).isTextual())throw invalid();
                var pin=new ProductPromptRegisterApproval.Pin(entry.get("workspaceId").textValue(),
                        entry.get("approvalRef").textValue(),entry.get("approvalSha").textValue());
                if(!workspaces.add(pin.workspaceId())||!references.add(pin.approvalRef())||!digests.add(pin.approvalSha()))throw invalid();
                result.add(pin);
            }
            return List.copyOf(result);
        }catch(java.io.IOException failed){throw new IllegalArgumentException("Prompt REGISTER pins invalid",failed);}
    }

    private static String required(Environment env,String name) {
        String value=env.getProperty(PREFIX+name);if(value==null||value.isBlank())throw invalid();return value;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Prompt REGISTER configuration invalid");}

    private static void schema(JdbcTemplate main,JdbcTemplate authority) {
        String receipt="request_id,receipt_id,workspace_id,operation,subject_id,operator_organization,maintainer_version,"
                +"certificate_sha,key_sha,request_sha,workspace_sha,result_sha,prepared_at,receipt_until";
        main.queryForList("SELECT organization_id,status FROM con3_organization WHERE 1=0");
        main.queryForList("SELECT workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until FROM conp_workspace WHERE 1=0");
        main.queryForList("SELECT "+receipt+" FROM conp_workspace_register_receipt WHERE 1=0");
        authority.queryForList("SELECT singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version FROM id2_maintainer WHERE 1=0");
        authority.queryForList("SELECT workspace_id,organization,scope_version,state,policy_ref,policy_sha,registry_sha,valid_until,"
                +"control_state,registry_intent_id,expected_registry_sha,intent_until FROM idp_workspace WHERE 1=0");
        authority.queryForList("SELECT "+receipt+",organization,scope_version,workspace_state,policy_ref,policy_sha,registry_sha,valid_until,"
                +"primary_database_sha,authority_database_sha,approval_ref,approval_sha,approved_receipt_until,intent_until,prepared_sha,state "
                +"FROM idp_workspace_register_request WHERE 1=0");
    }
}
