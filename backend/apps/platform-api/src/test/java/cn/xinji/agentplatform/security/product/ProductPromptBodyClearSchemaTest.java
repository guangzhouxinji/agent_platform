package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecordsFixture.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class ProductPromptBodyClearSchemaTest {
    @Test void bothForwardMigrationsSupportExactFiniteRowsAndRejectForksAndBadCoordinates()throws Exception{
        for(boolean authority:new boolean[]{false,true}){
            JdbcTemplate db=database(authority);var request=fixed();
            Map<String,Object> original=authority?codec().requestColumns(request):codec().receiptColumns(request.receipt());
            String table=authority?"idp_prompt_cleanup_request":"conp_prompt_cleanup_receipt";
            insert(db,table,original);
            if(authority)assertThat(db.query("SELECT "+ProductPromptBodyClearCodec.REQUEST_COLUMNS+" FROM "+table,(rs,n)->codec().readRequest(rs))).containsExactly(request);
            else assertThat(db.query("SELECT "+ProductPromptBodyClearCodec.COLUMNS+" FROM "+table,(rs,n)->codec().readReceipt(rs))).containsExactly(request.receipt());
            for(String failure:new String[]{"request","receipt","revision","before","after","workspaceFk","revisionFk","headFk","operation","generation","intent","scope"}){
                var changed=fresh(original);
                switch(failure){
                    case "request" -> changed.put("request_id",original.get("request_id"));
                    case "receipt" -> changed.put("receipt_id",original.get("receipt_id"));
                    case "revision" -> changed.put("revision_id",REVISION);
                    case "before" -> changed.put("before_sha",original.get("before_sha"));
                    case "after" -> changed.put("after_sha",original.get("after_sha"));
                    case "workspaceFk" -> changed.put("workspace_id",UUID.randomUUID().toString());
                    case "revisionFk" -> changed.put("revision_id",UUID.randomUUID().toString());
                    case "headFk" -> changed.put("head_revision_id",UUID.randomUUID().toString());
                    case "operation" -> changed.put("operation","REGISTER");
                    case "generation" -> changed.put("generation",0L);
                    case "intent" -> changed.put("intent_until",AT);
                    case "scope" -> changed.put("closure_scope_until",request.receiptUntil().plusMillis(1));
                    default -> throw new AssertionError(failure);
                }
                assertThatThrownBy(()->insert(db,table,changed)).as((authority?"authority ":"main ")+failure).isInstanceOf(RuntimeException.class);
            }
            var valid=fresh(original);insert(db,table,valid);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isEqualTo(2);
        }
    }
    @Test void cleanupMarkerRequiresItsExactStateAndNoBusinessIntent()throws Exception{
        JdbcTemplate db=database(true);var request=fixed();insert(db,"idp_prompt_cleanup_request",codec().requestColumns(request));
        for(String field:new String[]{"intent_id","operation","key_sha","request_sha","before_sha","expected_after_sha","target_generation","target_revision_id","intent_until"}){
            Object value=field.equals("target_generation")?3L:field.equals("intent_until")?Timestamp.from(AT.plusSeconds(30)):
                    field.equals("operation")?"SAVE":field.endsWith("sha")?"a".repeat(64):UUID.randomUUID().toString();
            assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET "+field+"=? WHERE prompt_id=?",value,PROMPT)).as("mixed READY "+field).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET cleanup_request_id=? WHERE prompt_id=?",request.requestId(),PROMPT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET state='CLEANUP_PENDING',cleanup_request_id=? WHERE prompt_id=?",UUID.randomUUID().toString(),PROMPT)).isInstanceOf(RuntimeException.class);
        assertThat(db.update("UPDATE idp_prompt_control SET state='CLEANUP_PENDING',cleanup_request_id=? WHERE prompt_id=?",request.requestId(),PROMPT)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET operation='SAVE' WHERE prompt_id=?",PROMPT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET intent_id=? WHERE prompt_id=?",UUID.randomUUID().toString(),PROMPT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET state='READY' WHERE prompt_id=?",PROMPT)).isInstanceOf(RuntimeException.class);
        assertThat(db.update("UPDATE idp_prompt_control SET state='READY',cleanup_request_id=NULL WHERE prompt_id=?",PROMPT)).isEqualTo(1);
        var key="a".repeat(64);
        assertThat(db.update("UPDATE idp_prompt_control SET state='PENDING',intent_id=?,operation='SAVE',key_sha=?,request_sha=?,before_sha=?,"
                +"expected_after_sha=?,target_generation=3,target_revision_id=?,intent_until=? WHERE prompt_id=?",
                UUID.randomUUID().toString(),key,key,key,key,UUID.randomUUID().toString(),Timestamp.from(AT.plusSeconds(30)),PROMPT)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET operation=NULL WHERE prompt_id=?",PROMPT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET target_generation=NULL WHERE prompt_id=?",PROMPT)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->db.update("UPDATE idp_prompt_control SET cleanup_request_id=? WHERE prompt_id=?",request.requestId(),PROMPT)).isInstanceOf(RuntimeException.class);
    }
    private static LinkedHashMap<String,Object> fresh(Map<String,Object> original){
        var changed=new LinkedHashMap<>(original);changed.put("request_id",UUID.randomUUID().toString());changed.put("receipt_id",UUID.randomUUID().toString());
        changed.put("revision_id",HEAD);changed.put("key_sha","e".repeat(64));changed.put("before_sha","f".repeat(64));changed.put("after_sha","0".repeat(64));return changed;
    }
    private static JdbcTemplate database(boolean authority)throws Exception{
        var source=new DriverManagerDataSource("jdbc:h2:mem:clear-schema-"+UUID.randomUUID()+";MODE=Oracle;DB_CLOSE_DELAY=-1","sa","");
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();while(!Files.isDirectory(root.resolve("db")))root=root.getParent();
        try(var connection=source.getConnection()){
            for(String migration:authority?new String[]{"identity-authority/V007__product_prompt_control.sql","identity-authority/V012__product_prompt_body_clear.sql"}
                    :new String[]{"dm8/V112__product_prompt_drafts.sql","dm8/V115__product_prompt_body_clear.sql"})
                ScriptUtils.executeSqlScript(connection,new EncodedResource(new FileSystemResource(root.resolve("db").resolve(migration)),"UTF-8"));
        }
        JdbcTemplate db=new JdbcTemplate(source);var r=fixed();String prefix=authority?"idp_":"conp_";
        var w=r.workspace();
        if(authority)db.update("INSERT INTO idp_workspace VALUES(?,?,?,?,?,?,?,?,'READY',NULL,?,?)",w.id(),w.organization(),w.scopeVersion(),w.state(),w.policyRef(),w.policySha(),w.registrySha(),Timestamp.from(w.validUntil()),w.registrySha(),Timestamp.from(AT.plusSeconds(30)));
        else db.update("INSERT INTO conp_workspace VALUES(?,?,?,?,?,?,?,?)",w.id(),w.organization(),w.scopeVersion(),w.state(),w.policyRef(),w.policySha(),w.registrySha(),Timestamp.from(w.validUntil()));
        if(authority)db.update("INSERT INTO idp_prompt_control(prompt_id,workspace_id,kind,generation,head_revision_id,scalar_sha,state,container_until,control_until) VALUES(?,?,'GUIDE',2,?,?,'READY',?,?)",
                PROMPT,WORKSPACE,HEAD,r.before().scalarSha(),Timestamp.from(r.before().containerUntil()),Timestamp.from(r.before().controlUntil()));
        else db.update("INSERT INTO conp_prompt VALUES(?,?,'GUIDE',?,2,?,?,?)",PROMPT,WORKSPACE,HEAD,r.before().scalarSha(),Timestamp.from(r.before().containerUntil()),Timestamp.from(r.before().controlUntil()));
        var t=r.target();
        for(String revision:new String[]{REVISION,HEAD}){
            if(authority)db.update("INSERT INTO idp_revision_witness VALUES(?,?,?,?,?,?,?,?,'PRESENT',?,?,?,?,?)",revision,PROMPT,revision.equals(REVISION)?1:2,
                    t.recordSha(),t.contentSha(),Timestamp.from(t.submittedAt()),Timestamp.from(t.bodyUntil()),Timestamp.from(t.auditUntil()),"a".repeat(64),"b".repeat(64),"c".repeat(64),"issuer","creator");
            else db.update("INSERT INTO "+prefix+"revision VALUES(?,?,?,'owned schema body',?,?,?,?,?,'PRESENT')",revision,PROMPT,revision.equals(REVISION)?1:2,
                    t.contentSha(),t.recordSha(),Timestamp.from(t.submittedAt()),Timestamp.from(t.bodyUntil()),Timestamp.from(t.auditUntil()));
        }
        return db;
    }
    private static void insert(JdbcTemplate db,String table,Map<String,Object> values){
        assertThat(db.update("INSERT INTO "+table+"("+String.join(",",values.keySet())+") VALUES("+String.join(",",Collections.nCopies(values.size(),"?"))+")",
                values.values().stream().map(v->v instanceof Instant at?Timestamp.from(at):v).toArray())).isEqualTo(1);
    }
}
