package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.security.product.ProductPromptRecords.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.VariableDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProductPromptPersistenceTest {
    private Fixture f;
    @BeforeEach void setup() throws Exception { f=new Fixture(); }

    @Test void commitsImmutableDraftAndExactOriginalKeyWithoutRenewal() {
        Write write=f.write(id(),1,"Original draft",Operation.CREATE,id());
        Receipt receipt=f.appendReady(write);
        Instant until=f.main.queryForObject("SELECT body_until FROM conp_revision",java.sql.Timestamp.class).toInstant();
        f.now=f.now.plusSeconds(60);
        Receipt replay=f.mainTx.execute(status->f.store.append(write));
        assertThat(replay).isEqualTo(receipt);
        assertThat(f.count("conp_revision")).isEqualTo(1);
        assertThat(f.count("conp_event")).isEqualTo(1);
        assertThat(f.count("conp_receipt")).isEqualTo(1);
        assertThat(f.main.queryForObject("SELECT body_until FROM conp_revision",java.sql.Timestamp.class).toInstant()).isEqualTo(until);
        assertThat(f.read(receipt)).isEqualTo(write.revision());
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("READY");
    }

    @Test void newRevisionDoesNotOverwriteOrExtendOriginalAndPagesInSql() {
        String prompt=id();
        Receipt first=f.appendReady(f.write(prompt,1,"First",Operation.CREATE,id()));
        Instant firstUntil=f.main.queryForObject("SELECT body_until FROM conp_revision WHERE revision_id=?",java.sql.Timestamp.class,first.revisionId()).toInstant();
        f.now=f.now.plus(Duration.ofDays(1));
        Receipt second=f.appendReady(f.write(prompt,2,"Second",Operation.SAVE,id()));
        assertThat(f.read(first).body()).isEqualTo("First");
        assertThat(f.read(second).body()).isEqualTo("Second");
        assertThat(f.main.queryForObject("SELECT body_until FROM conp_revision WHERE revision_id=?",java.sql.Timestamp.class,first.revisionId()).toInstant()).isEqualTo(firstUntil);
        Page page=f.mainTx.execute(status->f.store.history(prompt,1,10,f.controls));
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.revisions()).extracting(Header::revisionId).containsExactly(second.revisionId(),first.revisionId());
        assertThat(f.mainTx.execute(status->f.store.history(prompt,2,10,f.controls)).revisions()).isEmpty();
    }

    @Test void decimalDefaultSurvivesImmutableContentAuthentication() {
        String prompt=id(),revision=id();
        var definitions=List.of(new VariableDefinition("ratio",VariableDefinition.Type.DECIMAL,false,"declared-source",true,new java.math.BigDecimal("1.00"),false));
        var reference=PromptRevision.referenceFor(prompt,revision,1,PromptRevision.Kind.SYSTEM,"Decimal","Ratio {{ratio}}",Set.of(),Set.of(),"internal","zh",0,definitions);
        var value=new PromptRevision(reference,PromptRevision.Kind.SYSTEM,"Decimal","Ratio {{ratio}}",Set.of(),Set.of(),"internal","zh",0,definitions,f.provenance());
        Receipt receipt=f.appendReady(new Write(f.workspace.id(),value,0,Operation.CREATE,"issuer","human",hash(id()),hash(id()),f.now.plus(Duration.ofDays(90)),f.now.plus(Duration.ofDays(180))));
        assertThat(f.read(receipt)).isEqualTo(value);
    }

    @Test void staleCasAndSameKeyDifferentDigestCannotChangePersistedDraft() {
        String prompt=id(),key=id();Write first=f.write(prompt,1,"First",Operation.CREATE,key);
        f.appendReady(first);
        Write different=f.write(prompt,1,"Different",Operation.CREATE,key);
        assertThatThrownBy(()->f.mainTx.execute(status->f.store.append(different))).isInstanceOf(IllegalStateException.class);
        f.appendReady(f.write(prompt,2,"Second",Operation.SAVE,id()));
        assertThatThrownBy(()->f.mainTx.execute(status->f.store.append(f.write(prompt,2,"Stale",Operation.SAVE,id())))).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("conp_revision")).isEqualTo(2);
        assertThat(f.count("conp_event")).isEqualTo(2);
    }

    @Test void intermediateInsertFailureRollsBackEvenWhenCallerCatchesIt() {
        f.failEvent=true;Write write=f.write(id(),1,"Rollback",Operation.CREATE,id());
        assertThatThrownBy(()->f.mainTx.execute(status->{
            assertThatThrownBy(()->f.store.append(write)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            return null;
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        for(String table:List.of("conp_prompt","conp_revision","conp_event","conp_receipt"))assertThat(f.count(table)).isZero();
    }

    @Test void independentPendingCommitSurvivesMainRollbackAndClosesReads() {
        Write write=f.write(id(),1,"Pending",Operation.CREATE,id());
        assertThatThrownBy(()->f.mainTx.execute(status->{
            PreparedWrite prepared=f.store.prepare(write);
            Pending pending=f.pending(prepared);
            f.authorityTx.executeWithoutResult(other->f.controls.reserve(pending));
            assertThat(f.independent.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
            f.store.append(prepared);
            throw new IllegalStateException("main failure after pending committed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(f.count("conp_revision")).isZero();
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM idp_revision_witness",Integer.class)).isZero();
    }

    @Test void committedMainWithoutIndependentReadyRemainsUnreadable() {
        Write write=f.write(id(),1,"Not ready",Operation.CREATE,id());
        Receipt receipt=f.mainTx.execute(status->{PreparedWrite prepared=f.store.prepare(write);
            f.authorityTx.executeWithoutResult(other->f.controls.reserve(f.pending(prepared)));return f.store.append(prepared);});
        assertThatThrownBy(()->f.read(receipt)).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
        assertThat(f.count("conp_revision")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings={"expires","missing-witness","cleared-witness","pending","registry-drift","scalar-drift"})
    void deadlineOrIndependentStateRejectsBeforeBodySelect(String failure) {
        Write write=f.write(id(),1,"Protected",Operation.CREATE,id());Receipt receipt=f.appendReady(write);
        switch(failure){
            case "expires"->f.now=write.bodyUntil();
            case "missing-witness"->f.authority.update("DELETE FROM idp_revision_witness");
            case "cleared-witness"->f.authority.update("UPDATE idp_revision_witness SET body_state='CLEARED',content_sha=NULL");
            case "pending"->f.authorityTx.executeWithoutResult(status->f.controls.reserve(new Pending(receipt.promptId(),f.workspace.id(),write.revision().kind(),
                    1,receipt.revisionId(),f.authority.queryForObject("SELECT scalar_sha FROM idp_prompt_control",String.class),hash("next"),id(),id(),Operation.SAVE,
                    hash("next-key"),hash("next-command"),f.now.plusSeconds(600),write.bodyUntil(),write.auditUntil())));
            case "registry-drift"->f.authority.update("UPDATE idp_workspace SET organization='foreign'");
            case "scalar-drift"->f.main.update("UPDATE conp_receipt SET result_sha=?",hash("changed"));
            default->throw new AssertionError(failure);
        }
        assertThatThrownBy(()->f.read(receipt)).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
    }

    @Test void corruptJsonIsNotReturnedAndCannotBeMistakenForMissingObject() {
        Receipt receipt=f.appendReady(f.write(id(),1,"Original",Operation.CREATE,id()));
        f.main.update("UPDATE conp_revision SET revision_json='{}'");
        assertThatThrownBy(()->f.read(receipt)).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isEqualTo(1);
    }

    @Test void oldMainHeadCannotBeAcceptedAgainstNewIndependentGeneration() {
        String prompt=id();Receipt old=f.appendReady(f.write(prompt,1,"Old",Operation.CREATE,id()));
        Head oldHead=f.mainTx.execute(status->f.store.head(prompt,false));
        f.appendReady(f.write(prompt,2,"New",Operation.SAVE,id()));
        f.main.update("UPDATE conp_prompt SET head_revision_id=?,generation=?,scalar_sha=? WHERE prompt_id=?",oldHead.revisionId(),oldHead.generation(),oldHead.scalarSha(),prompt);
        assertThatThrownBy(()->f.read(old)).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();
    }

    @Test void wrongIntentReadyCompletionRollsBackWitnessAndKeepsPending() {
        Write write=f.write(id(),1,"Exact intent",Operation.CREATE,id());
        Completion prepared=f.mainTx.execute(status->{PreparedWrite value=f.store.prepare(write);Pending pending=f.pending(value);
            f.authorityTx.executeWithoutResult(other->f.controls.reserve(pending));return f.completion(f.store.append(value),pending);});
        Pending original=prepared.pending();Pending wrong=new Pending(original.promptId(),original.workspaceId(),original.kind(),0,null,original.beforeSha(),
                original.afterSha(),original.targetRevisionId(),id(),original.operation(),original.keySha(),original.requestSha(),original.intentUntil(),original.containerUntil(),original.controlUntil());
        assertThatThrownBy(()->f.authorityTx.executeWithoutResult(status->f.controls.complete(wrong,prepared.head(),prepared.header(),prepared.receipt())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(f.authority.queryForObject("SELECT COUNT(*) FROM idp_revision_witness",Integer.class)).isZero();
        assertThat(f.authority.queryForObject("SELECT state FROM idp_prompt_control",String.class)).isEqualTo("PENDING");
        assertThat(f.authority.queryForObject("SELECT intent_id FROM idp_prompt_control",String.class)).isEqualTo(original.intentId());
    }

    @Test void realTwoConnectionCompareAndSetAllowsOnlyOneSameGenerationSave() throws Exception {
        String prompt=id();f.appendReady(f.write(prompt,1,"Base",Operation.CREATE,id()));
        Write one=f.write(prompt,2,"One",Operation.SAVE,id()),two=f.write(prompt,2,"Two",Operation.SAVE,id());
        var start=new CountDownLatch(1);var winners=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->race(one,start,winners));var b=executor.submit(()->race(two,start,winners));
            start.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);
        }
        assertThat(winners.get()).isEqualTo(1);assertThat(f.count("conp_revision")).isEqualTo(2);
        assertThat(f.authority.queryForObject("SELECT generation FROM idp_prompt_control",Long.class)).isEqualTo(2);
    }

    @Test void noTransactionWrongManagerAndWrongBoundConnectionAreRefused() {
        Write write=f.write(id(),1,"Bound",Operation.CREATE,id());
        assertThatThrownBy(()->f.store.append(write)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ProductPromptStore(f.main,new DataSourceTransactionManager(f.authoritySource),f.mapper,f.clock)).isInstanceOf(IllegalArgumentException.class);
        f.authorityTx.executeWithoutResult(status->assertThatThrownBy(()->f.store.append(write)).isInstanceOf(IllegalStateException.class));
        assertThat(f.count("conp_revision")).isZero();
    }

    @Test void finiteBoundsAndExpiredOriginalReceiptCannotBeRenewed() {
        Write write=f.write(id(),1,"Finite",Operation.CREATE,id());f.appendReady(write);
        assertThatThrownBy(()->new Write(write.workspaceId(),write.revision(),0,Operation.CREATE,"issuer","human",hash(id()),hash(id()),
                f.now.plus(Duration.ofDays(91)),f.now.plus(Duration.ofDays(180)))).isInstanceOf(IllegalArgumentException.class);
        f.now=write.auditUntil();f.traceReads.set(0);
        assertThatThrownBy(()->f.mainTx.execute(status->f.store.append(write))).isInstanceOf(IllegalStateException.class);
        assertThat(f.traceReads.get()).isZero();
        assertThat(f.count("conp_revision")).isEqualTo(1);
    }

    @Test void wrongIsolationAndUnsynchronizedManualHolderAreRejected() throws Exception {
        var wrong=new TransactionTemplate(f.mainManager);
        wrong.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        wrong.executeWithoutResult(status->assertThatThrownBy(()->f.store.workspace(f.workspace.id(),false)).isInstanceOf(IllegalStateException.class));
        try(var connection=f.mainSource.getConnection()){
            connection.setAutoCommit(false);
            var holder=new org.springframework.jdbc.datasource.ConnectionHolder(connection);
            org.springframework.transaction.support.TransactionSynchronizationManager.bindResource(f.mainSource,holder);
            org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(()->f.store.workspace(f.workspace.id(),false)).isInstanceOf(IllegalStateException.class); }
            finally { org.springframework.transaction.support.TransactionSynchronizationManager.unbindResource(f.mainSource);
                org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }
        }
    }

    @Test void expiredOldTraceClosesNewValidRevisionBeforeSelectingAnyActor() {
        String prompt=id();f.appendReady(f.write(prompt,1,"Old",Operation.CREATE,id()));
        f.now=f.now.plus(Duration.ofDays(179));
        Receipt latest=f.appendReady(f.write(prompt,2,"Still current",Operation.SAVE,id()));
        f.now=f.now.plus(Duration.ofDays(1));f.traceReads.set(0);
        assertThatThrownBy(()->f.read(latest)).isInstanceOf(IllegalStateException.class);
        assertThat(f.bodyReads.get()).isZero();assertThat(f.traceReads.get()).isZero();
    }

    @Test void preparedDigestIsKnownBeforeIndependentPendingAndMainMutation() {
        Write write=f.write(id(),1,"Prepared",Operation.CREATE,id());
        PreparedWrite prepared=f.mainTx.execute(status->f.store.prepare(write));
        assertThat(f.count("conp_prompt")).isZero();assertThat(f.count("conp_revision")).isZero();
        Pending pending=f.pending(prepared);
        f.authorityTx.executeWithoutResult(status->f.controls.reserve(pending));
        assertThat(f.authority.queryForObject("SELECT expected_after_sha FROM idp_prompt_control",String.class)).isEqualTo(prepared.after().scalarSha());
        assertThat(f.count("conp_prompt")).isZero();
        Receipt receipt=f.mainTx.execute(status->f.store.append(prepared));
        Completion committed=f.mainTx.execute(status->f.completion(receipt,pending));
        assertThat(committed.head()).isEqualTo(prepared.after());assertThat(receipt).isEqualTo(prepared.receipt());
        f.authorityTx.executeWithoutResult(status->f.controls.complete(pending,committed.head(),committed.header(),receipt));
        assertThat(f.read(receipt)).isEqualTo(write.revision());
    }

    @Test void legitimateLegacyArrayOrderReadsWithOriginalWitnessAndNoRewrite(){
        Write write=richWrite();Receipt receipt=f.appendReady(write);
        var tree=f.original(receipt);legacyOrder(tree);String raw=f.exactJson().canonical(tree);
        String record=f.installLegacy(receipt,raw);
        assertThat(record).isNotEqualTo(f.exactJson().sha(write.revision()));
        PromptRevision read=f.read(receipt);
        assertThat(read).isEqualTo(write.revision());
        assertThat(read.variables().getFirst().defaultValue()).isEqualTo(new java.math.BigDecimal("1.00"));
        assertThat(f.main.queryForObject("SELECT revision_json FROM conp_revision",String.class)).isEqualTo(raw);
        assertThat(f.main.queryForObject("SELECT record_sha FROM conp_revision",String.class)).isEqualTo(record);
        assertThat(f.authority.queryForObject("SELECT record_sha FROM idp_revision_witness",String.class)).isEqualTo(record);
    }

    @ParameterizedTest @ValueSource(strings={"array-order","body"})
    void originalJsonTamperCannotHideBehindEquivalentDomainSet(String mutation){
        Receipt receipt=f.appendReady(richWrite());var original=f.original(receipt);legacyOrder(original);
        f.installLegacy(receipt,f.exactJson().canonical(original));
        if("array-order".equals(mutation))original.putArray("scenarioIds").add("alpha").add("middle").add("zeta");
        else original.put("body","Changed {{ratio}}");
        f.main.update("UPDATE conp_revision SET revision_json=?",f.exactJson().canonical(original));
        assertThatThrownBy(()->f.read(receipt)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"duplicate-scenario","duplicate-intent","nontext-scenario","nonarray-intent","unknown-root","unknown-nested",
            "fractional-version","fractional-order","missing-nullable-field"})
    void witnessedRecordStillNeedsStrictDomainShape(String mutation){
        Receipt receipt=f.appendReady(richWrite());var original=f.original(receipt);legacyOrder(original);
        switch(mutation){
            case "duplicate-scenario"->original.withArray("scenarioIds").add("alpha");
            case "duplicate-intent"->original.withArray("intentIds").add("query");
            case "nontext-scenario"->original.withArray("scenarioIds").add(1);
            case "nonarray-intent"->original.put("intentIds","query");
            case "unknown-root"->original.put("injected",true);
            case "unknown-nested"->((com.fasterxml.jackson.databind.node.ObjectNode)original.get("provenance")).put("injected",true);
            case "fractional-version"->((com.fasterxml.jackson.databind.node.ObjectNode)original.get("reference")).put("version",new java.math.BigDecimal("1.5"));
            case "fractional-order"->original.put("displayOrder",new java.math.BigDecimal("0.5"));
            case "missing-nullable-field"->((com.fasterxml.jackson.databind.node.ObjectNode)original.get("provenance")).remove("contentApprovalRef");
            default->throw new AssertionError(mutation);
        }
        f.installLegacy(receipt,f.exactJson().canonical(original));
        assertThatThrownBy(()->f.read(receipt)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest @ValueSource(strings={"duplicate-key","trailing-json"})
    void matchingRecordCannotRelaxDuplicateKeyOrTrailingTokenParsing(String mutation){
        Receipt receipt=f.appendReady(richWrite());var original=f.original(receipt);legacyOrder(original);
        String raw=f.exactJson().canonical(original);f.installLegacy(receipt,raw);
        String invalid="duplicate-key".equals(mutation)?raw.substring(0,raw.length()-1)+",\"body\":\"Ratio {{ratio}}\"}":raw+" {}";
        f.main.update("UPDATE conp_revision SET revision_json=?",invalid);
        assertThatThrownBy(()->f.read(receipt)).isInstanceOf(IllegalStateException.class);
    }

    private Write richWrite(){
        var variables=List.of(new VariableDefinition("ratio",VariableDefinition.Type.DECIMAL,false,"source",true,new java.math.BigDecimal("1.00"),false));
        var scenarios=Set.of("zeta","alpha","middle");var intents=Set.of("search","query","answer");
        var ref=PromptRevision.referenceFor(id(),id(),1,PromptRevision.Kind.SYSTEM,"Draft","Ratio {{ratio}}",scenarios,intents,"internal","zh",0,variables);
        var value=new PromptRevision(ref,PromptRevision.Kind.SYSTEM,"Draft","Ratio {{ratio}}",scenarios,intents,"internal","zh",0,variables,f.provenance());
        return new Write(f.workspace.id(),value,0,Operation.CREATE,"issuer","human",hash(id()),hash("rich-command"),
                f.now.plus(Duration.ofDays(90)),f.now.plus(Duration.ofDays(180)));
    }
    private static void legacyOrder(com.fasterxml.jackson.databind.node.ObjectNode tree){
        tree.putArray("scenarioIds").add("zeta").add("alpha").add("middle");
        tree.putArray("intentIds").add("search").add("query").add("answer");
    }

    private void race(Write write,CountDownLatch start,AtomicInteger winners){
        try{start.await(5,TimeUnit.SECONDS);f.appendReady(write);winners.incrementAndGet();}
        catch(IllegalStateException conflict){assertThat(conflict.getMessage()).contains("conflict");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
    }
    private static String id(){return UUID.randomUUID().toString();}
    private static String hash(String value){return ProductComparisonJson.hash(value);}
    private record Completion(Receipt receipt,Pending pending,Head head,Header header) { }

    private static final class Fixture {
        volatile Instant now=Instant.parse("2026-10-13T00:00:00Z");
        final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}};
        final JdbcDataSource mainSource=source(),authoritySource=source();
        final AtomicInteger bodyReads=new AtomicInteger();
        final AtomicInteger traceReads=new AtomicInteger();
        boolean failEvent;
        final JdbcTemplate main=new JdbcTemplate(mainSource){
            @Override public int update(String sql,Object...args){if(failEvent&&sql.startsWith("INSERT INTO conp_event"))throw new org.springframework.dao.DataAccessResourceFailureException("event insert failure");return super.update(sql,args);}
            @Override public <T>List<T> query(String sql,RowMapper<T> mapper,Object...args){if(sql.startsWith("SELECT revision_json"))bodyReads.incrementAndGet();
                if(sql.startsWith("SELECT")&&sql.substring(0,sql.indexOf(" FROM ")).contains("subject_id"))traceReads.incrementAndGet();return super.query(sql,mapper,args);}
        };
        final JdbcTemplate authority=new JdbcTemplate(authoritySource),independent=new JdbcTemplate(authoritySource);
        final DataSourceTransactionManager mainManager=new DataSourceTransactionManager(mainSource),authorityManager=new DataSourceTransactionManager(authoritySource);
        final TransactionTemplate mainTx=new TransactionTemplate(mainManager),authorityTx=new TransactionTemplate(authorityManager);
        final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        final ProductPromptStore store=new ProductPromptStore(main,mainManager,mapper,clock);
        final ProductPromptAuthorityStore controls=new ProductPromptAuthorityStore(authority,authorityManager,clock);
        final Workspace workspace=new Workspace(id(),"organization",1,"ACTIVE","prompt-v1",hash("policy"),hash("registry"),now.plus(Duration.ofDays(365)));
        Fixture() throws Exception {
            migrate(mainSource,"db/dm8/V112__product_prompt_drafts.sql");migrate(authoritySource,"db/identity-authority/V007__product_prompt_control.sql");
            migrate(mainSource,"db/dm8/V115__product_prompt_body_clear.sql");migrate(authoritySource,"db/identity-authority/V012__product_prompt_body_clear.sql");
            String intent=id();authorityTx.executeWithoutResult(status->controls.insertWorkspacePending(workspace,intent,now.plusSeconds(600)));
            mainTx.executeWithoutResult(status->store.insertWorkspace(workspace));authorityTx.executeWithoutResult(status->controls.completeWorkspace(workspace,intent));
        }
        PromptRevision.Provenance provenance(){return new PromptRevision.Provenance("project-authored","human",now,"human",now,"human draft",List.of(),null,workspace.policyRef());}
        Write write(String prompt,long version,String body,Operation operation,String key){
            var reference=PromptRevision.referenceFor(prompt,id(),version,PromptRevision.Kind.SYSTEM,"Draft",body,Set.of(),Set.of(),"internal","zh",0,List.of());
            var revision=new PromptRevision(reference,PromptRevision.Kind.SYSTEM,"Draft",body,Set.of(),Set.of(),"internal","zh",0,List.of(),provenance());
            return new Write(workspace.id(),revision,version-1,operation,"issuer","human",hash(key),hash(body+":"+version),now.plus(Duration.ofDays(90)),now.plus(Duration.ofDays(180)));
        }
        Receipt appendReady(Write write){
            Completion done=mainTx.execute(status->{PreparedWrite prepared=store.prepare(write);Pending pending=pending(prepared);
                authorityTx.executeWithoutResult(other->controls.reserve(pending));return completion(store.append(prepared),pending);});
            authorityTx.executeWithoutResult(status->controls.complete(done.pending(),done.head(),done.header(),done.receipt()));return done.receipt();
        }
        Pending pending(PreparedWrite prepared){Write write=prepared.write();Receipt receipt=prepared.receipt();Head before=prepared.before(),head=prepared.after();
            return new Pending(receipt.promptId(),workspace.id(),write.revision().kind(),write.expectedGeneration(),before==null?null:before.revisionId(),
                    before==null?"0".repeat(64):before.scalarSha(),head.scalarSha(),receipt.revisionId(),id(),write.operation(),write.keySha(),write.requestSha(),
                    now.plusSeconds(600),head.containerUntil(),head.controlUntil());}
        Completion completion(Receipt receipt,Pending pending){return new Completion(receipt,pending,store.head(receipt.promptId(),false),store.header(receipt.promptId(),receipt.revisionId()));}
        PromptRevision read(Receipt receipt){return mainTx.execute(status->store.readRevision(receipt.promptId(),receipt.revisionId(),controls));}
        ProductComparisonJson exactJson(){return new ProductComparisonJson(mapper.copy()
                .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true))
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));}
        com.fasterxml.jackson.databind.node.ObjectNode original(Receipt receipt){
            return exactJson().stored(main.queryForObject("SELECT revision_json FROM conp_revision WHERE revision_id=?",String.class,receipt.revisionId()),
                    com.fasterxml.jackson.databind.node.ObjectNode.class);
        }
        String installLegacy(Receipt receipt,String raw){
            // Construct a historical SQL snapshot with its original scalar authority; production never rewrites it.
            String record=exactJson().sha(exactJson().stored(raw,com.fasterxml.jackson.databind.JsonNode.class));
            main.update("UPDATE conp_revision SET revision_json=?,record_sha=? WHERE revision_id=?",raw,record,receipt.revisionId());
            authority.update("UPDATE idp_revision_witness SET record_sha=? WHERE revision_id=?",record,receipt.revisionId());
            String closure=mainTx.execute(status->store.closureSha(receipt.promptId()));
            main.update("UPDATE conp_prompt SET scalar_sha=? WHERE prompt_id=?",closure,receipt.promptId());
            authority.update("UPDATE idp_prompt_control SET scalar_sha=? WHERE prompt_id=?",closure,receipt.promptId());return record;
        }
        int count(String table){return main.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
        private static JdbcDataSource source(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:prompt-"+id()+";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");return source;}
        private static void migrate(JdbcDataSource source,String file)throws Exception{try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of(file)));}}
    }
}
