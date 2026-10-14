package cn.xinji.agentplatform.enforcement.penalty;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.security.product.ProductGrants;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Narrow technical authorization fault preparation; not a human approval or domain I. */
class PenaltyTechnicalGrantRevokeIT {
    @Test void revokeOnlyThisControlledRuleTrialAndEditThroughFinalGrantServices()throws Exception {
        var data=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        assertEquals("jdbc:dm://127.0.0.1:5311",data.getUrl());var db=new JdbcTemplate(data);
        var authority=new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),required("ENF_ACCEPTANCE_AUTHORITY_USER"),required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")){
            @Override public java.sql.Connection getConnection()throws java.sql.SQLException {var c=super.getConnection();try{c.setSchema("ENFACC_AUTH");return c;}catch(java.sql.SQLException e){c.close();throw e;}}
        };
        var grants=new ProductGrants(db,new TransactionTemplate(new DataSourceTransactionManager(data)),Clock.systemUTC());
        var watermark=new ProductGrantWatermark(db,new JdbcTemplate(authority),required("ENF_ACCEPTANCE_ISSUER"));
        var receipts=new ArrayList<Object>();
        for(String action:List.of("TRIAL","EDIT")) {
            var rows=db.query("SELECT g.grant_id,g.grant_version FROM id2_grant g JOIN id2_current_epoch c ON c.current_grant_id=g.grant_id WHERE g.issuer=? AND g.subject_id=? AND g.organization=? AND g.domain_code='ENFORCEMENT' AND g.resource_type='PENALTY_RULE' AND g.resource_id=? AND g.resource_version=? AND g.action_code=? AND g.purpose='PENALTY_ANALYSIS' AND g.task_scope='ENF-PENALTY-FUNCTIONAL' AND g.data_scope=? AND g.status='ACTIVE'",
                (r,n)->new Grant(r.getString(1),r.getLong(2)),required("ENF_ACCEPTANCE_ISSUER"),required("ENF_ACCEPTANCE_SUBJECT"),required("ENF_ACCEPTANCE_ORGANIZATION"),required("ENF_PENALTY_RULE_ID"),Long.parseLong(System.getenv().getOrDefault(action.equals("TRIAL")?"ENF_PENALTY_REVOKE_TRIAL_REVISION":"ENF_PENALTY_REVOKE_EDIT_REVISION","1")),action,System.getenv().getOrDefault("ENF_PENALTY_REVOKE_DATA_SCOPE","FUNCTIONAL_VALIDATION"));
            assertEquals(1,rows.size(),"Only an exact existing controlled test grant may be revoked");var grant=rows.getFirst();
            receipts.add(watermark.change(()->grants.revoke(grant.id(),grant.version(),"codex-functional-validation",UUID.randomUUID().toString(),"a".repeat(64))));
        }
        new ObjectMapper().writeValue(Path.of(required("ENF_PENALTY_REVOCATION_EVIDENCE")).toFile(),Map.of("scope","EXACT_FUNCTIONAL_GRANT_FAULT_PREPARATION_NOT_BUSINESS_APPROVAL_OR_DOMAIN_I","ruleId",required("ENF_PENALTY_RULE_ID"),"receipts",receipts));
    }
    private record Grant(String id,long version){}
    static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
