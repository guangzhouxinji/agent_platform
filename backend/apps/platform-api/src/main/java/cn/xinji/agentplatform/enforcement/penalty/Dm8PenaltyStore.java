package cn.xinji.agentplatform.enforcement.penalty;

import static cn.xinji.agentplatform.enforcement.penalty.PenaltyContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Resource CAS and idempotency locks are separate; revision + event + receipt commit atomically in DM8. */
public final class Dm8PenaltyStore implements Store {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    private final PenaltyArtifacts files;
    public Dm8PenaltyStore(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,Clock clock,PenaltyArtifacts files) {
        this.db=db;this.tx=tx;this.json=json;this.clock=clock;this.files=java.util.Objects.requireNonNull(files);
    }
    @Override public Optional<Rule> rule(String id,long revision){return read("PENALTY_RULE",id,revision,Rule.class);}
    @Override public Optional<Rule> latestRule(String id){return latest("PENALTY_RULE",id,Rule.class);}
    @Override public List<Rule> ruleHistory(String id){return history("PENALTY_RULE",id,Rule.class);}
    @Override public Optional<Suggestion> suggestion(String id,long revision){return read("PENALTY_SUGGESTION",id,revision,Suggestion.class);}
    @Override public Optional<Suggestion> latestSuggestion(String id){return latest("PENALTY_SUGGESTION",id,Suggestion.class);}
    @Override public List<Suggestion> suggestionHistory(String id){return history("PENALTY_SUGGESTION",id,Suggestion.class);}
    private <T> Optional<T> read(String kind,String id,long revision,Class<T> type) {
        return db.query("SELECT payload,artifact_key,artifact_sha FROM enf11_penalty_revision WHERE resource_kind=? AND resource_id=? AND revision=?",
            (r,n)->decode(r.getString(1),r.getString(2),r.getString(3),type),kind,id,revision).stream().findFirst();
    }
    private <T> Optional<T> latest(String kind,String id,Class<T> type) {
        return db.query("SELECT payload,artifact_key,artifact_sha FROM enf11_penalty_revision WHERE resource_kind=? AND resource_id=? AND revision="
            +"(SELECT MAX(revision) FROM enf11_penalty_revision WHERE resource_kind=? AND resource_id=?)",
            (r,n)->decode(r.getString(1),r.getString(2),r.getString(3),type),kind,id,kind,id).stream().findFirst();
    }
    private <T> List<T> history(String kind,String id,Class<T> type) {
        return db.query("SELECT payload,artifact_key,artifact_sha FROM enf11_penalty_revision WHERE resource_kind=? AND resource_id=? ORDER BY revision",
            (r,n)->decode(r.getString(1),r.getString(2),r.getString(3),type),kind,id);
    }
    @Override public void appendRule(Rule r,long expected,String actor){append("PENALTY_RULE",r.id(),r.revision(),r.organization(),r.state().name(),r,expected,actor);}
    @Override public void appendSuggestion(Suggestion s,long expected,String actor){append("PENALTY_SUGGESTION",s.id(),s.revision(),s.organization(),s.state().name(),s,expected,actor);}
    private void append(String kind,String id,long revision,String org,String state,Object value,long expected,String actor) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Command transaction required");
        lock(16+Math.floorMod((kind+id).hashCode(),16));
        var heads=db.query("SELECT organization_id,revision FROM enf11_penalty_revision WHERE resource_kind=? AND resource_id=? ORDER BY revision DESC",
            (r,n)->new Head(r.getString(1),r.getLong(2)),kind,id);
        long current=heads.isEmpty()?0:heads.getFirst().revision();
        if(current!=expected||revision!=expected+1)throw new IllegalArgumentException("Penalty version conflict");
        if(!heads.isEmpty()&&!heads.getFirst().org().equals(org))throw new SecurityException("Organization mismatch");
        String payload=encode(value);var artifact=files.write(payload.getBytes(StandardCharsets.UTF_8));
        db.update("INSERT INTO enf11_penalty_revision(resource_kind,resource_id,revision,organization_id,state,payload,artifact_key,artifact_sha,actor_ref,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
            kind,id,revision,org,state,payload,artifact.key(),artifact.sha256(),actor,Timestamp.from(clock.instant()));
    }
    @Override public Receipt command(String key,String fingerprint,String actor,String operation,Supplier<Receipt> mutation) {
        if(key==null||key.isBlank()||key.length()>120||fingerprint==null||!fingerprint.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact command required");
        return tx.execute(status->{
            lock(Math.floorMod(key.hashCode(),16));
            var prior=db.query("SELECT fingerprint,actor_ref,operation,resource_kind,resource_id,revision FROM enf11_penalty_receipt WHERE request_key=?",
                (r,n)->new Stored(r.getString(1),r.getString(2),new Receipt(key,r.getString(3),r.getString(4),r.getString(5),r.getLong(6))),key).stream().findFirst();
            if(prior.isPresent()) {
                Stored s=prior.get();if(!fingerprint.equals(s.fingerprint())||!actor.equals(s.actor())||!operation.equals(s.receipt().operation()))throw new IllegalArgumentException("Idempotency conflict");
                return s.receipt();
            }
            Receipt r=mutation.get();
            if(!key.equals(r.requestId())||!operation.equals(r.operation()))throw new IllegalStateException("Command receipt mismatch");
            Integer count=db.queryForObject("SELECT COUNT(*) FROM enf11_penalty_revision WHERE resource_kind=? AND resource_id=? AND revision=?",Integer.class,r.resourceKind(),r.resourceId(),r.revision());
            if(count==null||count!=1)throw new IllegalStateException("Receipt revision absent");
            Timestamp now=Timestamp.from(clock.instant());
            db.update("INSERT INTO enf11_penalty_receipt(request_key,fingerprint,actor_ref,operation,resource_kind,resource_id,revision,created_at) VALUES(?,?,?,?,?,?,?,?)",
                key,fingerprint,actor,operation,r.resourceKind(),r.resourceId(),r.revision(),now);
            db.update("INSERT INTO enf11_penalty_event(event_id,request_key,resource_kind,resource_id,revision,actor_ref,operation,created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),key,r.resourceKind(),r.resourceId(),r.revision(),actor,operation,now);return r;
        });
    }
    private void lock(int bucket){db.queryForObject("SELECT bucket_id FROM enf11_penalty_lock WHERE bucket_id=? FOR UPDATE",Integer.class,bucket);}
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Penalty serialization failed",e);}
    }
    private <T> T decode(String payload,String key,String sha,Class<T> type) {
        byte[] content=files.read(key,sha,payload.getBytes(StandardCharsets.UTF_8));
        try{return json.readValue(content,type);}catch(java.io.IOException e){throw new IllegalStateException("Penalty original invalid",e);}
    }
    private record Head(String org,long revision) {}
    private record Stored(String fingerprint,String actor,Receipt receipt) {}
}
