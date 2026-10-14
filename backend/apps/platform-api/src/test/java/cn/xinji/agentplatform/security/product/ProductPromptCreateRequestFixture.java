package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared database and clock fixture for the original request-store contract tests. */
final class ProductPromptCreateRequestFixture {
    Instant now=Instant.parse("2026-10-14T00:00:00Z");
    final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}};
    final JdbcDataSource mainSource=source(),authoritySource=source();
    final AtomicInteger sensitiveReads=new AtomicInteger();boolean failRequestInsert;
    final JdbcTemplate main=new JdbcTemplate(mainSource);
    final JdbcTemplate authority=new JdbcTemplate(authoritySource){
        @Override public int update(String sql,Object...args){
            if(failRequestInsert&&sql.startsWith("INSERT INTO idp_prompt_request"))throw new DataAccessResourceFailureException("request insert failure");
            return super.update(sql,args);
        }
        @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object...args){
            if(sql.startsWith("SELECT request_id,"))sensitiveReads.incrementAndGet();return super.query(sql,mapper,args);
        }
    };
    final DataSourceTransactionManager mainManager=new DataSourceTransactionManager(mainSource),authorityManager=new DataSourceTransactionManager(authoritySource);
    final TransactionTemplate mainTx=new TransactionTemplate(mainManager),authorityTx=new TransactionTemplate(authorityManager);
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final ProductPromptStore store=new ProductPromptStore(main,mainManager,mapper,clock);
    final ProductPromptAuthorityStore controls=new ProductPromptAuthorityStore(authority,authorityManager,clock);
    final ProductPromptCreateRequestStore requests=new ProductPromptCreateRequestStore(controls,mapper,clock);
    final Workspace workspace;
    final ProductPromptCreateRequest.Evidence evidence;
    ProductPromptCreateRequestFixture()throws Exception{this(id());}
    ProductPromptCreateRequestFixture(String workspaceId)throws Exception{
        workspace=new Workspace(workspaceId,"organization",1,"ACTIVE","prompt-v1",hash("policy"),hash("registry"),now.plus(Duration.ofDays(365)));
        evidence=new ProductPromptCreateRequest.Evidence(1,workspace.registrySha(),"approval-v1",workspace.registrySha(),
                workspace.policyRef(),workspace.policySha(),"input-permission-v1",hash("input-permission"),"project-authored");
        migrate(mainSource,"db/dm8/V112__product_prompt_drafts.sql");migrate(authoritySource,"db/identity-authority/V007__product_prompt_control.sql");
        migrate(mainSource,"db/dm8/V115__product_prompt_body_clear.sql");migrate(authoritySource,"db/identity-authority/V012__product_prompt_body_clear.sql");
        migrate(authoritySource,"db/identity-authority/V008__product_prompt_create_request.sql");
        String intent=id();authorityTx.executeWithoutResult(status->controls.insertWorkspacePending(workspace,intent,now.plusSeconds(600)));
        mainTx.executeWithoutResult(status->store.insertWorkspace(workspace));authorityTx.executeWithoutResult(status->controls.completeWorkspace(workspace,intent));
    }
    Write write(String subject,String key,String body){
        var reference=PromptRevision.referenceFor(id(),id(),1,PromptRevision.Kind.SYSTEM,"Draft",body,Set.of(),Set.of(),"internal","zh",0,List.of());
        var origin=new PromptRevision.Provenance(evidence.generationSourceRef(),subject,now,subject,now,"CREATE_DRAFT",List.of(),evidence.inputPermissionRef(),workspace.policyRef());
        var revision=new PromptRevision(reference,PromptRevision.Kind.SYSTEM,"Draft",body,Set.of(),Set.of(),"internal","zh",0,List.of(),origin);
        return new Write(workspace.id(),revision,0,Operation.CREATE,"issuer",subject,key,hash(body),now.plus(Duration.ofDays(90)),now.plus(Duration.ofDays(180)));
    }
    ProductPromptCreateRequest prepared(String subject,String key,String body){return mainTx.execute(status->request(store.prepare(write(subject,key,body))));}
    ProductPromptCreateRequest request(PreparedWrite value){
        return requestWithEvidence(value,evidence);
    }
    ProductPromptCreateRequest requestWithEvidence(PreparedWrite value,ProductPromptCreateRequest.Evidence sourceEvidence){
        var r=value.receipt();var h=value.after();
        var p=new Pending(r.promptId(),workspace.id(),value.write().revision().kind(),0,null,"0".repeat(64),h.scalarSha(),r.revisionId(),id(),
                Operation.CREATE,r.keySha(),r.requestSha(),now.plusSeconds(600),h.containerUntil(),h.controlUntil());
        return requests.prepared(p,value,sourceEvidence);
    }
    ProductPromptCreateRequestStore reopen(){return new ProductPromptCreateRequestStore(new ProductPromptAuthorityStore(authority,authorityManager,clock),mapper,clock);}
    void reserve(ProductPromptCreateRequest request){authorityTx.executeWithoutResult(status->requests.reserve(request));}
    ProductPromptCreateRequest find(ProductPromptCreateRequest.Key key){return authorityTx.execute(status->requests.find(key));}
    int count(String table){return authority.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    int mainCount(String table){return main.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private static JdbcDataSource source(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:create-request-"+id()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");return source;}
    static void migrate(JdbcDataSource source,String file)throws Exception{try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of(file)));}}
    private static String id(){return UUID.randomUUID().toString();}
    private static String hash(String value){return ProductComparisonJson.hash(value);}
}
