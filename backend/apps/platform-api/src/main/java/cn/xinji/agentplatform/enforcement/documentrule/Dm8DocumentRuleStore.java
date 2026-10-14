package cn.xinji.agentplatform.enforcement.documentrule;

import static cn.xinji.agentplatform.enforcement.documentrule.DocumentRuleContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One transaction owns immutable revisions, receipts and events; resource CAS is independent of request keys. */
public final class Dm8DocumentRuleStore implements DocumentRuleService.Store {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateFiles files;
    public Dm8DocumentRuleStore(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Clock clock) {
        this(db,tx,json,clock,null);
    }
    public Dm8DocumentRuleStore(JdbcTemplate db, TransactionTemplate tx, ObjectMapper json, Clock clock,
            cn.xinji.agentplatform.enforcement.documenttemplate.DocumentTemplateFiles files) {
        this.db=db; this.tx=tx; this.json=json; this.clock=clock;
        this.files=files;
    }
    @Override public Optional<Rule> latest(String id) {
        return db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? AND revision="
                + "(SELECT MAX(revision) FROM enf10_document_rule WHERE rule_id=?)",
                (rs,n)->decode(rs.getString(1)),id,id).stream().findFirst();
    }
    @Override public Optional<Rule> read(String id,long revision) {
        return db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? AND revision=?",
                (rs,n)->decode(rs.getString(1)),id,revision).stream().findFirst();
    }
    @Override public List<Rule> history(String id) {
        return db.query("SELECT payload FROM enf10_document_rule WHERE rule_id=? ORDER BY revision",
                (rs,n)->decode(rs.getString(1)),id);
    }
    @Override public void append(Rule rule,long expected,String actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Rule mutation requires command transaction");
        lock(8+Math.floorMod(rule.id().hashCode(),8));
        var current=latest(rule.id());
        if (current.map(Rule::revision).orElse(0L)!=expected || rule.revision()!=expected+1)
            throw new IllegalArgumentException("Rule version conflict");
        if (current.isPresent() && !current.get().organization().equals(rule.organization()))
            throw new SecurityException("Rule organization mismatch");
        db.update("INSERT INTO enf10_document_rule(rule_id,revision,organization_id,state,payload,actor_ref,created_at) "
                + "VALUES(?,?,?,?,?,?,?)",rule.id(),rule.revision(),rule.organization(),rule.state().name(),
                encode(rule),actor,Timestamp.from(clock.instant()));
    }
    @Override public DocumentRuleService.Receipt command(String key,String fingerprint,String actor,String operation,
            Supplier<DocumentRuleService.Receipt> mutation) {
        if(key==null || key.isBlank() || key.length()>120) throw new IllegalArgumentException("Idempotency-Key required");
        return tx.execute(status->{
            lock(Math.floorMod(key.hashCode(),8));
            var prior=db.query("SELECT fingerprint,actor_ref,operation,rule_id,revision FROM enf10_document_rule_receipt WHERE request_key=?",
                (rs,n)->new Stored(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)),key).stream().findFirst();
            if(prior.isPresent()) {
                var p=prior.get();
                if(!p.fingerprint().equals(fingerprint)||!p.actor().equals(actor)||!p.operation().equals(operation))
                    throw new IllegalArgumentException("Idempotency receipt conflict");
                return new DocumentRuleService.Receipt(key,operation,p.id(),p.revision());
            }
            var receipt=mutation.get();
            if(!key.equals(receipt.requestId())||!operation.equals(receipt.operation()))
                throw new IllegalStateException("Receipt command mismatch");
            var now=Timestamp.from(clock.instant());
            db.update("INSERT INTO enf10_document_rule_receipt(request_key,fingerprint,actor_ref,operation,rule_id,revision,created_at) VALUES(?,?,?,?,?,?,?)",
                key,fingerprint,actor,operation,receipt.ruleId(),receipt.revision(),now);
            db.update("INSERT INTO enf10_document_rule_event(event_id,request_key,rule_id,revision,actor_ref,operation,created_at) VALUES(?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),key,receipt.ruleId(),receipt.revision(),actor,operation,now);
            return receipt;
        });
    }
    private void lock(int slot) {
        db.queryForObject("SELECT lock_id FROM enf10_document_rule_lock WHERE lock_id=? FOR UPDATE",Integer.class,slot);
    }
    @Override public void attempt(Attempt a,Runnable verify) {
        if(files==null)throw new IllegalStateException("Attempt artifact storage required");
        String payload;try{payload=json.writeValueAsString(a);}catch(Exception e){throw new IllegalStateException("Attempt serialization failed",e);}
        byte[] raw=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);String digest=sha(raw);
        String[] key={null};
        try {
            tx.executeWithoutResult(status->{
                verify.run();key[0]=files.put("document-rule-attempt",digest,raw);
                db.update("INSERT INTO enf10_document_rule_attempt(attempt_id,rule_id,revision,actor_ref,action_code,outcome,artifact_key,artifact_sha256,payload,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    a.id(),a.ruleId(),a.revision(),a.actor(),a.action(),a.outcome(),key[0],digest,payload,Timestamp.from(a.completedAt()));
                db.update("INSERT INTO enf10_document_rule_attempt_event(attempt_id,rule_id,revision,actor_ref,action_code,outcome,created_at) VALUES(?,?,?,?,?,?,?)",
                    a.id(),a.ruleId(),a.revision(),a.actor(),a.action(),a.outcome(),Timestamp.from(a.completedAt()));
                verify.run();
            });
        }catch(RuntimeException failure){if(key[0]!=null)try{files.delete(key[0]);}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    @Override public List<Attempt> attempts(String id) {
        if(files==null)throw new IllegalStateException("Attempt artifact storage required");
        return db.query("SELECT payload,artifact_key,artifact_sha256 FROM enf10_document_rule_attempt WHERE rule_id=? ORDER BY created_at,attempt_id",(rs,n)->{
            byte[] raw=files.read(rs.getString(2));
            if(!sha(raw).equals(rs.getString(3))||!new String(raw,java.nio.charset.StandardCharsets.UTF_8).equals(rs.getString(1)))throw new IllegalStateException("Attempt artifact mismatch");
            try{return json.readValue(raw,Attempt.class);}catch(Exception e){throw new IllegalStateException("Attempt artifact unreadable",e);}
        },id);
    }
    private static String sha(byte[] raw){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));}catch(Exception e){throw new IllegalStateException(e);}}
    private Rule decode(String payload) {
        try{return json.readValue(payload,Rule.class);}catch(Exception e){throw new IllegalStateException("Rule data unreadable",e);}
    }
    private String encode(Rule rule) {
        try{return json.writeValueAsString(rule);}catch(Exception e){throw new IllegalStateException("Rule serialization failed",e);}
    }
    private record Stored(String fingerprint,String actor,String operation,String id,long revision) {}
}
