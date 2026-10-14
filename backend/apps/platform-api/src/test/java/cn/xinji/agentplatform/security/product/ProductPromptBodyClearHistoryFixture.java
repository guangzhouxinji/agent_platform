package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import cn.xinji.agentplatform.security.product.ProductPromptBodyClearRecords.Request;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Completed conversion facts for history-consumer tests; the maintenance writer is tested separately. */
final class ProductPromptBodyClearHistoryFixture {
    final ProductPromptSaveApplicationTest saves=new ProductPromptSaveApplicationTest();
    final ProductPromptCreateApplicationTest.Fixture f;
    final TransactionTemplate mainTx,authorityTx;
    final ProductPromptBodyClearCodec codec;
    final Instant start;
    ProductPromptBodyClearHistoryFixture(Path root)throws Exception {
        saves.root=root;saves.setup();f=saves.f;start=f.base.now;
        mainTx=new TransactionTemplate(f.mainManager);authorityTx=new TransactionTemplate(f.authorityManager);
        codec=new ProductPromptBodyClearCodec(new ProductComparisonJson(f.base.mapper));
    }
    Receipt created(){return saves.created;}
    void at(long seconds){f.base.now=start.plusSeconds(seconds);}
    Receipt save(Receipt base,String key)throws Exception {
        var command=saves.permit(base,"member","Complete owned draft "+key+" {{ratio}}");
        return saves.app().request(f.base.token,base.promptId(),key,command);
    }
    Receipt lookup(String key){return saves.app().lookup(f.base.token,f.base.workspaceId,key);}
    Request clear(Receipt target) {
        Request request=mainTx.execute(status->{
            Workspace workspace=f.store.workspace(f.base.workspaceId,true);Head before=f.store.head(target.promptId(),true);
            Header header=f.store.header(target.promptId(),target.revisionId());var snapshot=f.store.scalarSnapshot(before);
            var evidence=new ProductPromptBodyClearApproval.Evidence("owned-cleaner",workspace.organization(),"a".repeat(64),1,
                    "b".repeat(64),"c".repeat(64),"owned-clear.json","d".repeat(64),header.auditUntil());
            var key=new ProductPromptBodyClearRecords.Key(workspace.id(),before.promptId(),header.revisionId(),evidence.operatorSubject(),
                    evidence.certificateSha(),ProductComparisonJson.hash(UUID.randomUUID().toString()));
            return codec.prepare(workspace,evidence,before,header,target,key,snapshot.clear(header).sha(),f.base.now,
                    snapshot.closureScopeUntil(workspace),header.auditUntil()).ready();
        });
        authorityTx.executeWithoutResult(status->insert(f.authority,"idp_prompt_cleanup_request",codec.requestColumns(request)));
        mainTx.executeWithoutResult(status->{
            f.main.update("UPDATE conp_revision SET revision_json=NULL,body_state='CLEARED' WHERE revision_id=?",target.revisionId());
            f.main.update("UPDATE conp_prompt SET scalar_sha=? WHERE prompt_id=?",request.afterSha(),target.promptId());
            insert(f.main,"conp_prompt_cleanup_receipt",codec.receiptColumns(request.receipt()));
        });
        authorityTx.executeWithoutResult(status->{
            f.authority.update("UPDATE idp_revision_witness SET body_state='CLEARED' WHERE revision_id=?",target.revisionId());
            f.authority.update("UPDATE idp_prompt_control SET scalar_sha=? WHERE prompt_id=?",request.afterSha(),target.promptId());
        });
        return request;
    }
    Request replace(Request old,Head before,String afterSha) {
        Request replacement=codec.prepare(old.workspace(),old.evidence(),before,old.target(),old.originalReceipt(),old.key(),afterSha,
                old.firstAttempt(),old.closureScopeUntil(),old.intentUntil()).ready();
        f.base.db.update("DELETE FROM conp_prompt_cleanup_receipt WHERE request_id=?",old.requestId());
        f.base.independent.update("DELETE FROM idp_prompt_cleanup_request WHERE request_id=?",old.requestId());
        insert(f.base.db,"conp_prompt_cleanup_receipt",codec.receiptColumns(replacement.receipt()));
        insert(f.base.independent,"idp_prompt_cleanup_request",codec.requestColumns(replacement));return replacement;
    }
    String originalPrepared(String key) {
        return f.base.independent.queryForObject("SELECT prepared_sha FROM idp_prompt_request WHERE operation='SAVE' AND key_sha=?",
                String.class,ProductComparisonJson.hash(key));
    }
    void clearStatements(){f.mainSource.statements.clear();f.authoritySource.statements.clear();}
    private static void insert(JdbcTemplate jdbc,String table,Map<String,Object> columns) {
        jdbc.update("INSERT INTO "+table+"("+String.join(",",columns.keySet())+") VALUES("
                        +String.join(",",Collections.nCopies(columns.size(),"?"))+")",
                columns.values().stream().map(v->v instanceof Instant at?Timestamp.from(at):v).toArray());
    }
}
