package cn.xinji.agentplatform.security.product;

import static cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

/** Source scope and each exact current grant remain separate from Task READ and field uses. */
final class ProductTrademarkReadSources {
    record Scope(String id,long version,long pointerVersion,String organization,long taskVersion,String source,
                 String contract,String decision,long epoch,long rowVersion,String status,String pointerStatus,Instant until) { }
    record Key(String type,String id,String action,String purpose) { }
    record Grant(String id,long generation,long sourceEpoch,long version,Instant until) { }
    record Plan(List<Scope> scopes,Map<Key,Grant> grants) {
        Plan { scopes=List.copyOf(scopes); grants=Map.copyOf(grants); }
        Instant until() { return grants.values().stream().map(Grant::until).min(Instant::compareTo).orElseThrow(); }
    }
    record Witness(Scope scope,ProductSourceAvailability.Active active,Map<String,Instant> uses,Instant until) {
        Witness { uses=Map.copyOf(uses); }
    }
    private static final Comparator<Key> ORDER=Comparator.comparing(Key::type).thenComparing(Key::id)
            .thenComparing(Key::action).thenComparing(Key::purpose);
    private final JdbcTemplate db;
    private final ProductSourceWatermark watermark;
    private final ProductSourceAvailability availability;
    private final Clock clock;
    private final Set<String> configured;
    ProductTrademarkReadSources(JdbcTemplate db,ProductSourceWatermark watermark,Clock clock,Set<String> configured) {
        this.db=new JdbcTemplate(db.getDataSource());this.db.setQueryTimeout(5); this.watermark=watermark; this.clock=clock; this.configured=Set.copyOf(configured);
        availability=new ProductSourceAvailability(db,watermark,clock);
    }
    Plan plan(ProductTaskDirectory.Task task,Jwt jwt) {
        var keys=new TreeMap<Key,Grant>(ORDER);
        Key taskKey=new Key("TASK",task.id(),"READ","TASK_WORK");
        keys.put(taskKey,grant(task,jwt,taskKey,task.authorizationVersion(),task.dataScope(),false).orElseThrow(()->new Failure(Code.ACCESS_DENIED)));
        var selected=new ArrayList<Scope>();
        for(Scope scope:scopes(task.id())) {
            if(!configured.contains(scope.source()))continue;
            Key query=new Key("SOURCE_SCOPE",scope.id(),"QUERY","SOURCE_QUERY"),display=new Key("SOURCE_SCOPE",scope.id(),"DISPLAY","SOURCE_DISPLAY");
            var q=grant(task,jwt,query,scope.version(),scope.id()+":"+scope.version(),false);
            var d=grant(task,jwt,display,scope.version(),scope.id()+":"+scope.version(),false);
            if(q.isEmpty()||d.isEmpty())continue;
            require(task.organization().equals(scope.organization())&&task.version()==scope.taskVersion()
                    &&"ACTIVE".equals(scope.status())&&"ACTIVE".equals(scope.pointerStatus())&&clock.instant().isBefore(scope.until()),Code.ACCESS_DENIED);
            selected.add(scope); keys.put(query,q.get());keys.put(display,d.get());
        }
        require(!selected.isEmpty(),Code.CAPABILITY_UNAVAILABLE); require(selected.size()<=8,Code.CAPABILITY_UNAVAILABLE);
        return new Plan(selected,keys);
    }
    void lock(ProductTaskDirectory.Task task,Jwt jwt,Plan plan) {
        var ordered=new TreeSet<Key>(ORDER);ordered.addAll(plan.grants().keySet());
        for(Key key:ordered) {
            var rows=db.queryForList("SELECT current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? "
                    +"AND domain_code='CONSULTATION' AND resource_type=? AND resource_id=? AND action_code=? AND purpose=? FOR UPDATE",
                    String.class,jwt.getIssuer().toString(),jwt.getSubject(),task.organization(),key.type(),key.id(),key.action(),key.purpose());
            require(rows.size()==1&&plan.grants().get(key).id().equals(rows.getFirst()),Code.ACCESS_DENIED);
            one("SELECT grant_id FROM id2_grant WHERE grant_id=? FOR UPDATE",rows.getFirst());
        }
        for(Scope scope:plan.scopes())one("SELECT source_id FROM con4_source_current WHERE source_id=? FOR UPDATE",scope.source());
        for(Scope scope:plan.scopes()) {
            one("SELECT scope_id FROM con4_task_source_current WHERE task_id=? AND source_id=? FOR UPDATE",task.id(),scope.source());
            one("SELECT scope_id FROM con4_task_source_scope WHERE scope_id=? AND scope_version=? FOR UPDATE",scope.id(),scope.version());
        }
        for(Scope scope:plan.scopes()) {
            one("SELECT source_id FROM con4_source_contract WHERE source_id=? AND contract_version=? FOR UPDATE",scope.source(),scope.contract());
            db.queryForList("SELECT field_code FROM con4_source_field_use WHERE decision_id=? ORDER BY field_code,use_code FOR UPDATE",String.class,scope.decision());
        }
        require(plan.equals(plan(task,jwt)),Code.ACCESS_DENIED);
    }
    Witness witness(Scope scope) {
        var current=watermark.current(scope.source());
        require("ACTIVE".equals(current.status())&&current.contractVersion().equals(scope.contract())&&current.decisionId().equals(scope.decision())
                &&current.epoch()==scope.epoch()&&current.rowVersion()==scope.rowVersion(),Code.SOURCE_UNAVAILABLE);
        var uses=new TreeMap<String,Instant>();
        db.query("SELECT field_code,use_code,valid_until FROM con4_source_field_use WHERE decision_id=? AND use_code IN ('QUERY','DISPLAY')",
                rs->{ uses.put(rs.getString(2)+":"+rs.getString(1),rs.getTimestamp(3).toInstant()); },scope.decision());
        var rows=db.query("SELECT k.source_version,v.manifest_ref,v.manifest_sha,v.records_sha,k.valid_until,a.valid_until "
                +"FROM con4_source_contract k JOIN con4_source_admission a ON a.source_id=k.source_id AND a.contract_version=k.contract_version "
                +"JOIN con4_source_verification v ON v.source_id=k.source_id AND v.contract_version=k.contract_version AND v.source_version=k.source_version "
                +"JOIN con4_source_product_decision d ON d.decision_id=a.decision_id "
                +"WHERE k.source_id=? AND k.contract_version=? AND a.decision_id=? AND k.status='ACTIVE' AND k.row_version=2 "
                +"AND v.candidate_row_version=1 AND d.rights_evidence_status<>'WITHDRAWN'",(rs,n)->new Witness(scope,
                        new ProductSourceAvailability.Active(scope.source(),scope.contract(),rs.getString(1),scope.decision(),scope.epoch(),scope.rowVersion(),
                                rs.getString(2),rs.getString(3),rs.getString(4)),uses,minimum(scope.until(),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6).toInstant())),
                scope.source(),scope.contract(),scope.decision());
        require(rows.size()==1&&clock.instant().isBefore(rows.getFirst().until()),Code.SOURCE_UNAVAILABLE);
        return rows.getFirst();
    }
    Instant requireFields(Witness witness,Set<Field> fields,String use) {
        Instant until=witness.until();
        if(!fields.isEmpty()) {
            var names=new TreeSet<String>(); fields.forEach(field->names.add(field.name()));
            require(witness.active().equals(availability.requireFields(witness.scope().source(),names,use)),Code.SOURCE_UNAVAILABLE);
            for(String field:names) {
                Instant deadline=witness.uses().get(use+":"+field);
                Instant current=availability.useUntil(witness.scope().source(),field,use);
                require(deadline!=null&&!current.isAfter(deadline),Code.SOURCE_UNAVAILABLE);
                until=minimum(until,current);
            }
        }
        require(clock.instant().isBefore(until),Code.SOURCE_UNAVAILABLE);return until;
    }
    Set<Field> displayFields(Witness witness) {
        var fields=EnumSet.noneOf(Field.class);
        for(Field field:Field.values()) { Instant until=witness.uses().get("DISPLAY:"+field.name());if(until!=null&&clock.instant().isBefore(until))fields.add(field); }
        return Set.copyOf(fields);
    }
    private Optional<Grant> grant(ProductTaskDirectory.Task task,Jwt jwt,Key key,long version,String scope,boolean locking) {
        var grants=db.query("SELECT g.grant_id,g.grant_generation,c.source_epoch,g.grant_version,g.expires_at FROM id2_current_epoch c "
                +"JOIN id2_grant g ON g.grant_id=c.current_grant_id AND g.issuer=c.issuer AND g.subject_id=c.subject_id "
                +"AND g.organization=c.organization AND g.domain_code=c.domain_code AND g.resource_type=c.resource_type AND g.resource_id=c.resource_id "
                +"AND g.action_code=c.action_code AND g.purpose=c.purpose AND g.grant_generation=c.authorization_epoch "
                +"WHERE c.issuer=? AND c.subject_id=? AND c.organization=? AND c.domain_code='CONSULTATION' "
                +"AND c.resource_type=? AND c.resource_id=? AND c.action_code=? AND c.purpose=? "
                +"AND g.task_scope=? AND g.data_scope=? AND g.resource_version=? AND g.status='ACTIVE'",
                (rs,n)->new Grant(rs.getString(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getTimestamp(5).toInstant()),
                jwt.getIssuer().toString(),jwt.getSubject(),task.organization(),key.type(),key.id(),key.action(),key.purpose(),task.id(),scope,version);
        require(grants.size()<=1,Code.SOURCE_UNAVAILABLE);
        return grants.stream().filter(g->clock.instant().isBefore(g.until())).findFirst();
    }
    private List<Scope> scopes(String task) {
        return db.query("SELECT s.scope_id,s.scope_version,c.row_version,s.organization_id,s.task_version,s.source_id,s.contract_version,"
                +"s.decision_id,s.source_epoch,s.source_row_version,s.status,c.status,s.expires_at "
                +"FROM con4_task_source_current c JOIN con4_task_source_scope s ON s.scope_id=c.scope_id AND s.scope_version=c.scope_version "
                +"AND s.task_id=c.task_id AND s.source_id=c.source_id WHERE c.task_id=? ORDER BY s.source_id",(rs,n)->new Scope(rs.getString(1),rs.getLong(2),
                        rs.getLong(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getLong(9),rs.getLong(10),
                        rs.getString(11),rs.getString(12),rs.getTimestamp(13).toInstant()),task);
    }
    private void one(String sql,Object... values) { require(db.queryForList(sql,String.class,values).size()==1,Code.SOURCE_UNAVAILABLE); }
    static Instant minimum(Instant... values) { return Arrays.stream(values).min(Instant::compareTo).orElseThrow(); }
}
