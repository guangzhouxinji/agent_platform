package cn.xinji.agentplatform.adjudication.casework;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;

/** Persistent allowance: uncertain/failed requests never disappear or refund their call count. */
public final class JdbcMaterialRunStore {
    public record Reservation(String id,String policyKey,String requestSha,long inputUpper,long outputUpper,Instant expiresAt,boolean replay) { }
    public record Totals(long calls,long inputTokens,long outputTokens,long costMicros,boolean frozen) { }
    private volatile boolean retentionHealthy=true;
    private final JdbcTemplate db; private final TransactionTemplate tx; private final MaterialRunVault vault; private final Clock clock;
    public JdbcMaterialRunStore(JdbcTemplate db,TransactionTemplate tx,MaterialRunVault vault,Clock clock){this.db=db;this.tx=tx;this.vault=vault;this.clock=clock;}
    public void initialize(MaterialModelPolicy p) {
        if(!p.validUntil().isAfter(clock.instant()))throw new SecurityException("MODEL_APPROVAL_EXPIRED");
        tx.executeWithoutResult(status->{
            if(db.queryForObject("SELECT COUNT(*) FROM adj3_model_budget WHERE policy_key=?",Integer.class,p.key())==0) {
                if(db.queryForObject("SELECT COUNT(*) FROM adj3_model_budget WHERE approval_key IS NULL",Integer.class)>0)throw new IllegalStateException("MODEL_APPROVAL_BINDING_RECONCILIATION_REQUIRED");
                db.update("INSERT INTO adj3_model_budget(policy_key,case_id,config_sha,expires_at,calls,input_tokens,output_tokens,cost_micros,frozen,last_seen,approval_key) VALUES(?,?,?,?,0,0,0,0,0,?,?)",p.key(),p.caseId(),p.configurationSha(),Timestamp.from(p.validUntil()),Timestamp.from(clock.instant()),p.approvalKey());
            } else db.update("UPDATE adj3_model_budget SET approval_key=? WHERE policy_key=? AND approval_key IS NULL",p.approvalKey(),p.key());
            lock(p);checkPolicy(p);
        });
    }
    public Reservation reserve(MaterialModelPolicy p,String requestKey,String actor,String requestSha,long inputUpper,int outputUpper) {
        if(requestKey==null || !requestKey.matches("[A-Za-z0-9_-]{1,100}") || actor==null || actor.isBlank() || requestSha==null || !requestSha.matches("[a-f0-9]{64}")
                || inputUpper<1 || inputUpper>100000 || outputUpper<1 || outputUpper>2048)throw new IllegalArgumentException("MODEL_RESERVATION_INVALID");
        return tx.execute(status->{
            lock(p);checkPolicy(p);
            var prior=db.query("SELECT run_id,actor_ref,request_sha,input_tokens,output_tokens,expires_at,state FROM adj3_material_run WHERE policy_key=? AND request_key=?",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getLong(5),r.getTimestamp(6).toInstant(),r.getString(7)},p.key(),requestKey);
            if(!prior.isEmpty()){
                var a=prior.getFirst();if(!actor.equals(a[1]) || !requestSha.equals(a[2]))throw new IllegalArgumentException("IDEMPOTENCY_KEY_CONFLICT");
                if(!"COMMITTED".equals(a[6]))throw new IllegalArgumentException("MODEL_ATTEMPT_NOT_RETRYABLE");
                return new Reservation((String)a[0],p.key(),requestSha,(long)a[3],(long)a[4],(Instant)a[5],true);
            }
            var t=totals(p);long cost=Math.addExact(Math.multiplyExact(inputUpper,2),Math.multiplyExact(outputUpper,8));
            if(t.frozen() || t.calls()>=20 || t.inputTokens()+inputUpper>100000 || t.outputTokens()+outputUpper>20000 || t.costMicros()+cost>10000000)throw new IllegalArgumentException("MODEL_BUDGET_EXHAUSTED");
            Instant now=clock.instant();String id=UUID.randomUUID().toString();Instant expires=now.plus(Duration.ofDays(30));if(p.validUntil().isBefore(expires))expires=p.validUntil();
            db.update("UPDATE adj3_model_budget SET calls=calls+1,input_tokens=input_tokens+?,output_tokens=output_tokens+?,cost_micros=cost_micros+?,last_seen=? WHERE policy_key=?",inputUpper,outputUpper,cost,Timestamp.from(now),p.key());
            db.update("INSERT INTO adj3_material_run(run_id,policy_key,request_key,actor_ref,request_sha,input_tokens,output_tokens,cost_micros,state,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,'RESERVED',?,?)",id,p.key(),requestKey,actor,requestSha,inputUpper,outputUpper,cost,Timestamp.from(now),Timestamp.from(expires));
            return new Reservation(id,p.key(),requestSha,inputUpper,outputUpper,expires,false);
        });
    }
    public void complete(MaterialModelPolicy p,Reservation r,long actualInput,long actualOutput,String exactPayload,boolean validCandidates) {
        if(r.replay())throw new IllegalArgumentException("MODEL_REPLAY_CANNOT_WRITE");
        if(actualInput<1 || actualOutput<1 || actualInput>r.inputUpper() || actualOutput>r.outputUpper()) {
            tx.executeWithoutResult(s->{lock(p);db.update("UPDATE adj3_model_budget SET frozen=1 WHERE policy_key=?",p.key());db.update("UPDATE adj3_material_run SET state='UNCERTAIN' WHERE run_id=? AND state='RESERVED'",r.id());});
            throw new IllegalStateException("MODEL_USAGE_EXCEEDED_RESERVATION");
        }
        String encrypted=vault.seal(r.id(),r.expiresAt(),exactPayload);
        tx.executeWithoutResult(status->{
            lock(p);checkPolicy(p);long actualCost=2*actualInput+8*actualOutput;
            int changed=db.update("UPDATE adj3_material_run SET input_tokens=?,output_tokens=?,cost_micros=?,state=?,payload=?,payload_sha=? WHERE run_id=? AND policy_key=? AND state='RESERVED'",ps->{
                ps.setLong(1,actualInput);ps.setLong(2,actualOutput);ps.setLong(3,actualCost);ps.setString(4,validCandidates?"COMMITTED":"REJECTED");
                ps.setCharacterStream(5,new java.io.StringReader(encrypted),encrypted.length());ps.setString(6,sha256(encrypted));ps.setString(7,r.id());ps.setString(8,p.key());
            });
            if(changed!=1)throw new IllegalArgumentException("MODEL_RUN_STATE_CONFLICT");
            long originalCost=2*r.inputUpper()+8*r.outputUpper();
            db.update("UPDATE adj3_model_budget SET input_tokens=input_tokens-?,output_tokens=output_tokens-?,cost_micros=cost_micros-?,last_seen=? WHERE policy_key=?",r.inputUpper()-actualInput,r.outputUpper()-actualOutput,originalCost-actualCost,Timestamp.from(clock.instant()),p.key());
        });
    }
    public boolean requestExists(MaterialModelPolicy p,String key,String actor) {
        return tx.execute(s->{lock(p);checkPolicy(p);return db.queryForObject("SELECT COUNT(*) FROM adj3_material_run WHERE policy_key=? AND request_key=? AND actor_ref=?",Integer.class,p.key(),key,actor)>0;});
    }
    public Reservation existing(MaterialModelPolicy p,String key,String actor,String fingerprint) {
        return tx.execute(status->{lock(p);checkPolicy(p);
            var rows=db.query("SELECT run_id,actor_ref,request_sha,input_tokens,output_tokens,expires_at,state FROM adj3_material_run WHERE policy_key=? AND request_key=?",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getString(3),r.getLong(4),r.getLong(5),r.getTimestamp(6).toInstant(),r.getString(7)},p.key(),key);
            if(rows.isEmpty())return null;var a=rows.getFirst();
            if(!actor.equals(a[1])||!fingerprint.equals(a[2]))throw new IllegalArgumentException("IDEMPOTENCY_KEY_CONFLICT");
            if(!"COMMITTED".equals(a[6]))throw new IllegalArgumentException("MODEL_ATTEMPT_NOT_RETRYABLE");
            return new Reservation((String)a[0],p.key(),fingerprint,(long)a[3],(long)a[4],(Instant)a[5],true);
        });
    }
    public void freeze(Reservation r){db.update("UPDATE adj3_model_budget SET frozen=1 WHERE policy_key=?",r.policyKey());uncertain(r);}
    public void uncertain(Reservation r){if(!r.replay())db.update("UPDATE adj3_material_run SET state='UNCERTAIN' WHERE run_id=? AND policy_key=? AND state='RESERVED'",r.id(),r.policyKey());}
    public String load(MaterialModelPolicy p,String runId) { return tx.execute(status -> loadLocked(p,runId)); }
    private String loadLocked(MaterialModelPolicy p,String runId) {
        lock(p);checkPolicy(p);var rows=db.query("SELECT expires_at,payload,payload_sha,state FROM adj3_material_run WHERE run_id=? AND policy_key=?",(r,n)->new Object[]{r.getTimestamp(1).toInstant(),r.getString(2),r.getString(3),r.getString(4)},runId,p.key());
        if(rows.size()!=1)throw new IllegalArgumentException("MODEL_RUN_NOT_FOUND");var a=rows.getFirst();
        if(!"COMMITTED".equals(a[3]))throw new IllegalArgumentException("MODEL_RUN_NOT_COMMITTED");String encrypted=(String)a[1];
        if(encrypted==null || !sha256(encrypted).equals(a[2]))throw new IllegalStateException("MODEL_PAYLOAD_INTEGRITY_FAILED");
        return vault.open(runId,(Instant)a[0],encrypted);
    }
    public Totals totals(MaterialModelPolicy p){return db.queryForObject("SELECT calls,input_tokens,output_tokens,cost_micros,frozen FROM adj3_model_budget WHERE policy_key=?",(r,n)->new Totals(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getInt(5)!=0),p.key());}
    public void purgeExpired(){retentionHealthy=false;vault.purgeExpired();tx.executeWithoutResult(s->{var now=Timestamp.from(clock.instant());db.update("DELETE FROM adj3_material_run WHERE expires_at<=?",now);db.update("DELETE FROM adj3_model_budget WHERE expires_at<=?",now);});retentionHealthy=true;}
    private void lock(MaterialModelPolicy p){db.queryForObject("SELECT policy_key FROM adj3_model_budget WHERE policy_key=? FOR UPDATE",String.class,p.key());}
    private void checkPolicy(MaterialModelPolicy p) {
        if(!retentionHealthy)throw new IllegalStateException("MODEL_RETENTION_PURGE_UNHEALTHY");
        var v=db.queryForObject("SELECT case_id,config_sha,expires_at,last_seen,approval_key FROM adj3_model_budget WHERE policy_key=?",(r,n)->new Object[]{r.getString(1),r.getString(2),r.getTimestamp(3).toInstant(),r.getTimestamp(4).toInstant(),r.getString(5)},p.key());
        Instant now=clock.instant();
        if(v==null || !p.approvalKey().equals(v[4]) || !p.caseId().equals(v[0]) || !p.configurationSha().equals(v[1]) || !p.validUntil().equals(v[2])
                || !p.validUntil().isAfter(now) || ((Instant)v[3]).isAfter(now.plusSeconds(1)))throw new SecurityException("MODEL_POLICY_OR_CLOCK_NOT_CURRENT");
        var totals=totals(p);var sums=db.queryForObject("SELECT COUNT(*),COALESCE(SUM(input_tokens),0),COALESCE(SUM(output_tokens),0),COALESCE(SUM(cost_micros),0) FROM adj3_material_run WHERE policy_key=?",(r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)},p.key());
        if(sums==null || totals.calls()!=sums[0] || totals.inputTokens()!=sums[1] || totals.outputTokens()!=sums[2] || totals.costMicros()!=sums[3])throw new IllegalStateException("MODEL_BUDGET_LEDGER_INTEGRITY_FAILED");
    }
}
