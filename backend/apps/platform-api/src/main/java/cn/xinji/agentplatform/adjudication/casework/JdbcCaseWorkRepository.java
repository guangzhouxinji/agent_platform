package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.*;

/** Same schema durable head, immutable revisions, audit and receipts commit in one CAS transaction. */
public final class JdbcCaseWorkRepository implements CaseWorkRepository {
    private record Head(Scope scope, long version, String sha) { }
    private record Snapshot(State state, String sha, String previous, String actor, String action) { }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final CaseWorkCodec codec;
    private final Clock clock;
    public JdbcCaseWorkRepository(JdbcTemplate db, TransactionTemplate tx, CaseWorkCodec codec, Clock clock) {
        this.db = db; this.tx = tx; this.codec = codec; this.clock = clock;
    }
    @Override public Receipt create(CaseInput input, String originalText, String key, String fingerprint, Access access) {
        commandInput(key, fingerprint);
        if (originalText == null || originalText.isBlank() || !sha256(originalText).equals(input.source().sha256()))
            throw new IllegalArgumentException("CASE_ORIGINAL_REQUIRED_OR_DRIFTED");
        long generation = access.generation(); Scope scope = Scope.of(input);
        access.require(scope, "CREATE"); access.sources(scope, List.of(input.source()), "SAVE");
        var result = tx.execute(status -> {
            lock(key); access.require(scope, "CREATE"); access.assertUnchanged(generation);
            var replay = replay(key, fingerprint, access.identity(), input.id(), "CREATE");
            if (replay != null) { guard(load(head(input.id(), true)).get(Math.toIntExact(replay.workVersion()-1)), access, "CREATE"); return replay; }
            if (!db.query("SELECT case_id FROM adj2_case_work WHERE case_id=?", (rs, row) -> rs.getString(1), input.id()).isEmpty())
                throw new IllegalArgumentException("CASE_ALREADY_EXISTS");
            db.update("INSERT INTO adj2_case_work(case_id,organization,data_scope,head_version,head_sha256) VALUES(?,?,?,0,NULL)",
                    input.id(), input.organization(), input.dataScope());
            original(input.id(),new Original(input.source(),originalText));
            var state = open(input, access.actor(), clock.instant()); codec.validate(state, scope);
            var receipt = persist(state, null, key, fingerprint, access.identity(), "CREATE");
            access.require(scope, "CREATE"); access.sources(scope, List.of(input.source()), "SAVE"); access.assertUnchanged(generation);
            return receipt;
        });
        access.require(scope, "CREATE"); access.sources(scope, List.of(input.source()), "SAVE"); access.assertUnchanged(generation);
        return result;
    }
    @Override public Receipt mutate(String caseId, long expectedVersion, String key, String fingerprint, String action,
                                    List<Source> incoming, Original original, Access access, UnaryOperator<State> operation) {
        commandInput(key, fingerprint); if (expectedVersion < 1) throw new IllegalArgumentException("INVALID_EXPECTED_VERSION");
        long generation = access.generation();
        String grantAction = "REVIEW".equals(action) ? "HUMAN_REVIEW" : "WRITE";
        State[] committed=new State[1];
        var result = tx.execute(status -> {
            lock(key); var head = head(caseId, true); access.require(head.scope(), grantAction);
            var snapshots = load(head); var prior = snapshots.getLast(); guard(prior, access, grantAction);
            access.sources(head.scope(), incoming, "SAVE"); access.assertUnchanged(generation);
            var replay = replay(key, fingerprint, access.identity(), caseId, action);
            if (replay != null) { committed[0]=snapshots.get(Math.toIntExact(replay.workVersion()-1));guard(committed[0], access, grantAction); return replay; }
            if (prior.version() != expectedVersion) throw new IllegalArgumentException("WORK_VERSION_CONFLICT");
            if (Set.of("GENERATE_REPORT", "REVISE_REPORT", "SUBMIT", "REVIEW").contains(action))
                access.sources(head.scope(), codec.sources(prior), "ANALYZE");
            var next = operation.apply(prior); codec.validate(next, head.scope()); codec.transition(prior, next);
            if(original!=null){if(!original.source().equals(next.caseInput().source()))throw new IllegalArgumentException("CASE_ORIGINAL_SOURCE_CONFLICT");original(caseId,original);}
            committed[0]=next;
            var receipt = persist(next, head.sha(), key, fingerprint, access.identity(), action);
            guard(next, access, grantAction); access.sources(head.scope(), incoming, "SAVE"); access.assertUnchanged(generation);
            return receipt;
        });
        guard(committed[0], access, grantAction);
        access.assertUnchanged(generation); return result;
    }
    @Override public State read(String caseId, Long version, Access access) {
        long generation=access.generation();
        var result=tx.execute(status->{
            var head=head(caseId,true);access.require(head.scope(),"READ");var history=load(head);
            long requested=version==null?head.version():version;
            if(requested<1 || requested>history.size())throw new IllegalArgumentException("CASE_VERSION_NOT_FOUND");
            var selected=history.get(Math.toIntExact(requested-1));guard(selected,access,"READ");access.assertUnchanged(generation);return selected;
        });
        guard(result,access,"READ");access.assertUnchanged(generation);return result;
    }
    @Override public List<Version> versions(String caseId, Access access) {
        long generation=access.generation();
        var result=tx.execute(status->{
            var head=head(caseId,true);access.require(head.scope(),"READ");var history=load(head);var versions=new ArrayList<Version>();
            for(var state:history){guard(state,access,"READ");versions.add(new Version(state.version(),state.inputVersion(),sha256(codec.encode(state)),state.audits().getLast()));}
            access.assertUnchanged(generation);return List.copyOf(versions);
        });access.assertUnchanged(generation);return result;
    }
    @Override public List<Original> originals(String caseId,Access access) {
        long generation=access.generation();
        var result=tx.execute(status->{
            var head=head(caseId,true);access.require(head.scope(),"READ");var history=load(head);var originals=originalTexts(caseId);var sources=new LinkedHashSet<Source>();
            for(var state:history){guard(state,access,"READ");sources.add(state.caseInput().source());}
            access.assertUnchanged(generation);return sources.stream().map(source->new Original(source,originals.get(source.sha256()))).toList();
        });access.assertUnchanged(generation);return result;
    }
    private void original(String id,Original original) {
        if(original.originalText()==null || original.originalText().isBlank() || !sha256(original.originalText()).equals(original.source().sha256()))
            throw new IllegalArgumentException("CASE_ORIGINAL_REQUIRED_OR_DRIFTED");
        if(db.queryForObject("SELECT COUNT(*) FROM adj2_case_original WHERE case_id=? AND source_sha256=?",Integer.class,id,original.source().sha256())==0)
            db.update("INSERT INTO adj2_case_original(case_id,source_sha256,original_text) VALUES(?,?,?)",ps->{ps.setString(1,id);ps.setString(2,original.source().sha256());ps.setCharacterStream(3,new StringReader(original.originalText()),original.originalText().length());});
    }
    private Map<String,String> originalTexts(String id) {
        var originals=new HashMap<String,String>();
        db.query("SELECT source_sha256,original_text FROM adj2_case_original WHERE case_id=?",rs->{String text=clob(rs,2);CaseWorkCodec.hash(text,rs.getString(1));originals.put(rs.getString(1),text);},id);
        return originals;
    }
    private void guard(State state, Access access, String action) {
        var scope = Scope.of(state.caseInput()); access.require(scope, action); access.sources(scope, codec.sources(state), "READ");
    }
    private Receipt persist(State state, String previous, String key, String fingerprint, String identity, String action) {
        String payload = codec.encode(state), hash = sha256(payload); var audit = state.audits().getLast();
        db.update("INSERT INTO adj2_case_revision(case_id,work_version,input_version,payload,payload_sha256,previous_sha256,actor_ref,action_code,created_at) VALUES(?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, state.caseInput().id()); ps.setLong(2, state.version()); ps.setLong(3, state.inputVersion());
            ps.setCharacterStream(4, new StringReader(payload), payload.length()); ps.setString(5, hash); ps.setString(6, previous);
            ps.setString(7, audit.actor()); ps.setString(8, audit.action()); ps.setTimestamp(9, Timestamp.from(audit.at()));
        });
        String auditPayload = codec.encode(audit);
        db.update("INSERT INTO adj2_case_audit(case_id,work_version,payload,payload_sha256) VALUES(?,?,?,?)",
                state.caseInput().id(), state.version(), auditPayload, sha256(auditPayload));
        Report report = state.reports().isEmpty() ? null : state.reports().getLast();
        var receipt = new Receipt(key, action, state.caseInput().id(), state.version(), state.inputVersion(),
                report == null ? null : report.revision(), report == null ? null : report.contentSha256(), hash);
        String receiptPayload = codec.encode(receipt);
        db.update("INSERT INTO adj2_case_receipt(request_key,fingerprint,actor_identity,case_id,work_version,action_code,payload,payload_sha256) VALUES(?,?,?,?,?,?,?,?)",
                key, fingerprint, identity, state.caseInput().id(), state.version(), action, receiptPayload, sha256(receiptPayload));
        int changed = db.update("UPDATE adj2_case_work SET head_version=?,head_sha256=? WHERE case_id=? AND head_version=? AND (head_sha256=? OR (head_sha256 IS NULL AND ? IS NULL))",
                state.version(), hash, state.caseInput().id(), state.version()-1, previous, previous);
        if (changed != 1) throw new IllegalArgumentException("WORK_VERSION_CONFLICT"); return receipt;
    }
    private List<State> load(Head head) {
        var rows = db.query("SELECT payload,payload_sha256,previous_sha256,actor_ref,action_code,work_version,input_version FROM adj2_case_revision WHERE case_id=? ORDER BY work_version",
                (rs, row) -> {
                    String payload = clob(rs, 1); CaseWorkCodec.hash(payload, rs.getString(2));
                    var state = codec.decode(payload, State.class);
                    if (state.version() != rs.getLong(6) || state.inputVersion() != rs.getLong(7)) broken();
                    return new Snapshot(state, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
                }, head.scope().caseId());
        var originals=originalTexts(head.scope().caseId());
        if(!originals.keySet().equals(rows.stream().map(r->r.state().caseInput().source().sha256()).collect(java.util.stream.Collectors.toSet())))broken();
        if (rows.isEmpty() || rows.size() != head.version() || !rows.getLast().sha().equals(head.sha())) broken();
        var audits = db.query("SELECT payload,payload_sha256,work_version FROM adj2_case_audit WHERE case_id=? ORDER BY work_version",
                (rs, row) -> {
                    String payload = clob(rs, 1); CaseWorkCodec.hash(payload, rs.getString(2)); var audit = codec.decode(payload, Audit.class);
                    if (audit.version() != rs.getLong(3)) broken(); return audit;
                }, head.scope().caseId());
        var receipts = db.query("SELECT payload,payload_sha256,work_version,request_key,action_code,actor_identity FROM adj2_case_receipt WHERE case_id=? ORDER BY work_version",
                (rs, row) -> {
                    String payload = clob(rs, 1); CaseWorkCodec.hash(payload, rs.getString(2)); var receipt = codec.decode(payload, Receipt.class);
                    if (receipt.workVersion() != rs.getLong(3) || !receipt.requestId().equals(rs.getString(4))
                            || !receipt.action().equals(rs.getString(5)) || !head.scope().caseId().equals(receipt.caseId())) broken();
                    if (!rows.get(Math.toIntExact(receipt.workVersion()-1)).state().audits().getLast().actor().equals(rs.getString(6))) broken();
                    return receipt;
                }, head.scope().caseId());
        if (audits.size() != rows.size() || receipts.size() != rows.size()) broken();
        for (int i = 0; i < rows.size(); i++) {
            var snapshot = rows.get(i); var state = snapshot.state(); codec.validate(state, head.scope());
            if (state.version() != i+1L || !Objects.equals(snapshot.previous(), i==0 ? null : rows.get(i-1).sha())) broken();
            if (i==0 && state.inputVersion()!=1) broken(); if (i>0) codec.transition(rows.get(i-1).state(), state);
            var audit = state.audits().getLast();
            if (!audit.equals(audits.get(i)) || !audit.actor().equals(snapshot.actor()) || !audit.action().equals(snapshot.action())) broken();
            if (!state.audits().equals(audits.subList(0,i+1))) broken();
            var receipt = receipts.get(i); if (!receipt.snapshotSha256().equals(snapshot.sha()) || receipt.inputVersion()!=state.inputVersion()) broken();
            var report = state.reports().isEmpty() ? null : state.reports().getLast();
            if (!Objects.equals(receipt.reportRevision(), report==null ? null : report.revision())
                    || !Objects.equals(receipt.reportSha256(), report==null ? null : report.contentSha256())) broken();
        }
        return rows.stream().map(Snapshot::state).toList();
    }
    private Head head(String id, boolean lock) {
        var rows = db.query("SELECT organization,data_scope,head_version,head_sha256 FROM adj2_case_work WHERE case_id=?"+(lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Head(new Scope(id,rs.getString(1),rs.getString(2)),rs.getLong(3),rs.getString(4)),id);
        if (rows.size()!=1) throw new IllegalArgumentException("CASE_NOT_FOUND"); return rows.getFirst();
    }
    private Receipt replay(String key,String fingerprint,String actor,String id,String action) {
        var rows=db.query("SELECT fingerprint,actor_identity,case_id,action_code,payload,payload_sha256 FROM adj2_case_receipt WHERE request_key=?",(rs,row)->{
            if (!fingerprint.equals(rs.getString(1)) || !actor.equals(rs.getString(2)) || !id.equals(rs.getString(3)) || !action.equals(rs.getString(4)))
                throw new IllegalArgumentException("IDEMPOTENCY_KEY_CONFLICT");
            String payload=clob(rs,5);CaseWorkCodec.hash(payload,rs.getString(6));return codec.decode(payload,Receipt.class);
        },key);return rows.isEmpty()?null:rows.getFirst();
    }
    private void lock(String key) { db.queryForObject("SELECT lock_id FROM adj2_case_command_lock WHERE lock_id=? FOR UPDATE",Integer.class,Math.floorMod(key.hashCode(),8)); }
    private void commandInput(String key,String fingerprint) {
        if (key==null || !key.matches("[A-Za-z0-9_-]{1,100}") || fingerprint==null || !fingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("IDEMPOTENCY_KEY_REQUIRED");
    }
    private static String clob(ResultSet rs,int column) throws SQLException {
        try(var reader=rs.getCharacterStream(column)){if(reader==null)broken();var text=new StringWriter();reader.transferTo(text);return text.toString();}
        catch(java.io.IOException failure){throw new SQLException("CLOB read failed",failure);}
    }
    private static void broken(){throw new IllegalStateException("CASE_PERSISTENCE_INTEGRITY_FAILED");}
}
