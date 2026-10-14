package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductAccessPolicy;
import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** User decisions reference immutable persisted members, never caller-created document versions. */
final class ProductPatentSelection {
    record Command(long expectedRevision, String resultSha, List<String> memberIds) { }
    record Member(String memberId, long ordinal, String sourceId, String documentId,
                  String documentVersion, String sourceVersion, String rawSha, String locatorRef) { }
    record Revision(String selectionId, String taskId, long revision, long taskVersion,
                    String resultSetId, long resultSetVersion, String resultSha,
                    String actorSubject, Instant createdAt, Instant expiresAt, List<Member> members) { }
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductPatentSearchExecution searches;
    private final ProductTaskDirectory tasks;
    private final ProductConsultationAccess access;
    private final Clock clock;

    ProductPatentSelection(JdbcTemplate db, TransactionTemplate transactions,
            ProductPatentSearchExecution searches, ProductTaskDirectory tasks,
            ProductConsultationAccess access, Clock clock) {
        this.db = db; this.transactions = transactions; this.searches = searches;
        this.tasks = tasks; this.access = access; this.clock = clock;
        db.queryForObject("SELECT COUNT(*) FROM con4_selection_revision", Long.class);
        db.queryForObject("SELECT COUNT(*) FROM con4_selection_member", Long.class);
    }

    Revision select(String taskId, String resultSetId, Command command, String requestId, Jwt jwt) {
        if (command == null || command.expectedRevision() < 0 || command.resultSha() == null
                || !command.resultSha().matches("[0-9a-f]{64}") || command.memberIds() == null
                || command.memberIds().isEmpty() || command.memberIds().size() > 1000
                || command.memberIds().stream().anyMatch(id -> id == null || !id.matches("[0-9a-fA-F-]{36}"))
                || new HashSet<>(command.memberIds()).size() != command.memberIds().size()
                || requestId == null || requestId.isBlank() || requestId.length() > 200) throw badRequest();
        List<String> ids = command.memberIds().stream().sorted().toList();
        String sha = sha(resultSetId + "\n" + command.expectedRevision() + "\n"
                + command.resultSha() + "\n" + String.join("\n", ids));
        requireWrite(taskId, jwt);
        return transactions.execute(status -> {
            if (db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",
                    (rs, row) -> rs.getString(1), taskId).size() != 1) throw missing();
            ProductTaskDirectory.Task task = requireWrite(taskId, jwt);
            var page = searches.read(taskId, resultSetId, 20, null, jwt);
            if (!page.resultSha().equals(command.resultSha())) throw conflict();
            List<String> prior = db.query("SELECT selection_id,request_sha FROM con4_selection_revision "
                            + "WHERE task_id=? AND actor_subject=? AND request_id=?", (rs, row) -> {
                        if (!sha.equals(rs.getString(2))) throw conflict();
                        return rs.getString(1);
                    }, taskId, jwt.getSubject(), requestId);
            if (!prior.isEmpty()) return read(taskId, resultSetId, prior.getFirst(), jwt);
            Long current = db.queryForObject("SELECT COALESCE(MAX(revision),0) FROM con4_selection_revision "
                    + "WHERE task_id=?", Long.class, taskId);
            if (current == null || current != command.expectedRevision()) throw conflict();
            List<Member> members = new ArrayList<>();
            for (String id : ids) {
                List<Member> rows = db.query("SELECT member_id,ordinal,source_id,document_id,document_version,"
                                + "source_version,raw_sha,locator_ref FROM con4_result_member "
                                + "WHERE result_set_id=? AND member_id=?", (rs, row) -> member(rs), resultSetId, id);
                if (rows.size() != 1) throw missing();
                members.add(rows.getFirst());
            }
            Instant expiry = db.queryForObject("SELECT a.expires_at FROM con4_search_attempt a "
                            + "JOIN con4_result_set r ON r.attempt_id=a.attempt_id WHERE r.result_set_id=?",
                    (rs, row) -> rs.getTimestamp(1).toInstant(), resultSetId);
            Instant now = clock.instant();
            if (expiry == null || !expiry.isAfter(now)) throw missing();
            String selectionId = UUID.randomUUID().toString();
            db.update("INSERT INTO con4_selection_revision(selection_id,task_id,revision,task_version,"
                            + "result_set_id,result_set_version,result_sha,actor_subject,request_id,request_sha,"
                            + "created_at,expires_at) VALUES(?,?,?,?,?,1,?,?,?,?,?,?)", selectionId,
                    taskId, current + 1, task.version(), resultSetId, command.resultSha(), jwt.getSubject(),
                    requestId, sha, Timestamp.from(now), Timestamp.from(expiry));
            for (Member member : members) db.update("INSERT INTO con4_selection_member(selection_id,"
                            + "result_set_id,member_id,ordinal,source_id,document_id,document_version,source_version,"
                            + "raw_sha,locator_ref) VALUES(?,?,?,?,?,?,?,?,?,?)", selectionId, resultSetId,
                    member.memberId(), member.ordinal(), member.sourceId(), member.documentId(),
                    member.documentVersion(), member.sourceVersion(), member.rawSha(), member.locatorRef());
            if (!task.equals(requireWrite(taskId, jwt))) throw conflict();
            return read(taskId, resultSetId, selectionId, jwt);
        });
    }

    Revision read(String taskId, String resultSetId, String selectionId, Jwt jwt) {
        var page = searches.read(taskId, resultSetId, 20, null, jwt);
        List<Revision> rows = db.query("SELECT revision,task_version,result_set_version,result_sha,"
                        + "actor_subject,created_at,expires_at FROM con4_selection_revision "
                        + "WHERE selection_id=? AND task_id=? AND result_set_id=? AND expires_at>?",
                (rs, row) -> new Revision(selectionId, taskId, rs.getLong(1), rs.getLong(2), resultSetId,
                        rs.getLong(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7).toInstant(), members(selectionId)),
                selectionId, taskId, resultSetId, Timestamp.from(clock.instant()));
        if (rows.size() != 1 || !page.resultSha().equals(rows.getFirst().resultSha())) throw missing();
        return rows.getFirst();
    }
    private List<Member> members(String id) {
        return db.query("SELECT member_id,ordinal,source_id,document_id,document_version,source_version,"
                        + "raw_sha,locator_ref FROM con4_selection_member WHERE selection_id=? ORDER BY ordinal",
                (rs, row) -> member(rs), id);
    }
    private static Member member(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Member(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8));
    }
    private ProductTaskDirectory.Task requireWrite(String taskId, Jwt jwt) {
        if (jwt == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var task = tasks.find(taskId).orElseThrow(ProductPatentSelection::missing);
        if (!"IN_PROGRESS".equals(task.state()) || !task.expiresAt().isAfter(clock.instant())
                || !access.permits(jwt, new ProductConsultationAccess.ResourceFacts(task.organization(),
                        task.id(), task.dataScope(), "TASK", task.id(), task.authorizationVersion()),
                        ProductAccessPolicy.Action.WRITE)) throw missing();
        return task;
    }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT); }
    private static ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
