package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static cn.xinji.agentplatform.security.product.ProductPromptCopyRequestFixture.*;
import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

class ProductPromptCopyRequestStoreTest {
    private ProductPromptCopyRequestFixture f;
    @BeforeEach void setup()throws Exception{f=new ProductPromptCopyRequestFixture();}

    @Test void reopenedStoreRetainsAllSevenSourceScalarsAndReconstructsExactOriginalBytes(){
        var copy=f.copy("copy");f.reserve(copy);var original=copy.request();
        var rows=f.base.authority.queryForMap("SELECT * FROM idp_prompt_request WHERE request_id=?",original.pending().intentId());
        var reopened=f.base.reopen();var found=f.base.authorityTx.execute(status->reopened.find(original.key()));
        assertThat(found).isEqualTo(original);assertThat(found.copySource()).isEqualTo(original.copySource());
        assertThat(found.preparedFormat()).isEqualTo("PROMPT_COPY_PREPARED_SCALARS/v1");
        assertThat(found.requestFormat()).isEqualTo("PROMPT_COPY_COMMAND/v1");
        assertThat(found.header().contentSha()).isEqualTo(f.source.request().header().contentSha());
        f.base.now=f.base.now.plusSeconds(10);
        f.base.mainTx.executeWithoutResult(status->{
            var rebuilt=f.base.store.prepare(copy.prepared().write(),found.receipt().id());
            reopened.requirePrepared(found,rebuilt);
            assertThat(f.base.requests.prepared(found.pending(),rebuilt,found.evidence(),found.copySource())).isEqualTo(found);
        });
        assertThat(f.base.authority.queryForMap("SELECT * FROM idp_prompt_request WHERE request_id=?",original.pending().intentId())).isEqualTo(rows);
        assertThat(f.base.mainCount("conp_prompt")).isEqualTo(1);
    }

    @Test void copyNamespaceCannotBeFoundAsCreateOrSaveAndOldConstructorsCannotDropSource(){
        var copy=f.copy("same");f.reserve(copy);var r=copy.request();
        for(var operation:new Operation[]{Operation.CREATE,Operation.SAVE})
            assertThat(f.base.find(new ProductPromptCreateRequest.Key(r.key().workspaceId(),r.key().issuer(),r.key().subject(),operation,r.key().keySha()))).isNull();
        assertThatThrownBy(()->new ProductPromptCreateRequest(r.pending(),r.receipt(),r.header(),r.evidence(),r.preparedFormat(),r.requestFormat(),r.state(),r.preparedSha()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->f.base.requests.prepared(r.pending(),copy.prepared(),r.evidence())).isInstanceOf(IllegalArgumentException.class);
        assertThat(f.base.find(r.key())).isEqualTo(r);
    }

    @ParameterizedTest @ValueSource(strings={"source_workspace_id","source_prompt_id","source_revision_id","source_revision_number","source_record_sha","source_body_until","source_audit_until"})
    void copyMigrationRejectsEveryMissingSourceScalar(String column){
        var row=f.copy("missing").request().columns();row.put(column,null);
        assertThatThrownBy(()->insert(row)).isInstanceOf(DataAccessException.class);
        assertThat(f.base.count("idp_prompt_request")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"body","audit","zero-source-version","same-prompt","same-revision","create-format","save-format"})
    void copyMigrationRejectsExpandedDeadlinesOrWrongIdentityAndFormat(String fault){
        var original=f.copy("invalid").request();var row=original.columns();
        switch(fault){
            case "body"->row.put("source_body_until",original.header().bodyUntil().minusMillis(1));
            case "audit"->row.put("source_audit_until",original.header().auditUntil().minusMillis(1));
            case "zero-source-version"->row.put("source_revision_number",0L);
            case "same-prompt"->row.put("source_prompt_id",original.pending().promptId());
            case "same-revision"->row.put("source_revision_id",original.pending().targetRevisionId());
            case "create-format"->row.put("prepared_format",ProductPromptCreateRequest.PREPARED_FORMAT);
            case "save-format"->row.put("request_format",ProductPromptCreateRequest.SAVE_REQUEST_FORMAT);
            default->throw new AssertionError(fault);
        }
        assertThatThrownBy(()->insert(row)).isInstanceOf(DataAccessException.class);
        assertThat(f.base.count("idp_prompt_request")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"CREATE","SAVE"})
    void v010RejectsNonCopySourceColumnsWithoutChangingOldColumnSets(String operation){
        var request="CREATE".equals(operation)?f.source.request():f.save(f.source,"save").request();
        assertThat(request.columns().keySet()).noneMatch(name->name.startsWith("source_"));
        var row=request.columns();row.put("request_id",id());row.put("receipt_id",id());row.put("target_revision_id",id());row.put("key_sha",hash("other"));
        row.put("source_record_sha",hash("unexpected-source"));
        assertThatThrownBy(()->insert(row)).isInstanceOf(DataAccessException.class);
        assertThat(f.base.count("idp_prompt_request")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"source_workspace_id","source_prompt_id","source_revision_id","source_revision_number","source_record_sha","source_body_until","source_audit_until"})
    void currentSchemaValidSourceTamperingStillFailsPreparedAuthentication(String column){
        var copy=f.copy("tamper");f.reserve(copy);var request=copy.request();
        Object changed=switch(column){
            case "source_revision_number"->2L;
            case "source_record_sha"->hash("changed-record");
            case "source_body_until"->ProductPromptJdbc.ts(request.copySource().sourceBodyUntil().plusMillis(1));
            case "source_audit_until"->ProductPromptJdbc.ts(request.copySource().sourceAuditUntil().plusMillis(1));
            default->id();
        };
        f.base.authority.update("UPDATE idp_prompt_request SET "+column+"=? WHERE request_id=?",changed,request.pending().intentId());
        assertThatThrownBy(()->f.find(copy)).isInstanceOf(IllegalStateException.class).hasMessage("Prompt persistence unavailable");
        assertThat(f.base.mainCount("conp_prompt")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"body","audit","same-prompt","same-revision","source-reference"})
    void inMemoryRequestAlsoRejectsForgedSourceRelation(String fault){
        var original=f.copy("forged").request();var s=original.copySource();var evidence=original.evidence();
        var changed=new ProductPromptCreateRequest.CopySource(s.sourceWorkspaceId(),"same-prompt".equals(fault)?original.pending().promptId():s.sourcePromptId(),
                "same-revision".equals(fault)?original.pending().targetRevisionId():s.sourceRevisionId(),s.sourceRevisionNumber(),s.sourceRecordSha(),
                "body".equals(fault)?s.sourceBodyUntil().minusMillis(1):s.sourceBodyUntil(),
                "audit".equals(fault)?s.sourceAuditUntil().minusMillis(1):s.sourceAuditUntil());
        var selected="source-reference".equals(fault)?f.base.evidence:evidence;
        assertThatThrownBy(()->new ProductPromptCreateRequest(original.pending(),original.receipt(),original.header(),selected,original.preparedFormat(),
                original.requestFormat(),original.state(),original.preparedSha(),changed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void expiredCopyAuditClosesBeforeSelectingSourceCoordinates(){
        var copy=f.copy("expiry");f.reserve(copy);int reads=f.base.sensitiveReads.get();f.base.now=copy.request().header().auditUntil();
        assertThatThrownBy(()->f.find(copy)).isInstanceOf(IllegalStateException.class);
        assertThat(f.base.sensitiveReads.get()).isEqualTo(reads);
    }

    private void insert(Map<String,Object> columns){
        f.base.authority.update("INSERT INTO idp_prompt_request("+String.join(",",columns.keySet())+") VALUES("
                        +String.join(",",Collections.nCopies(columns.size(),"?"))+")",
                columns.values().stream().map(value->value instanceof Instant at?ProductPromptJdbc.ts(at):value).toArray());
    }
}
