package cn.xinji.agentplatform.security.product;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Issues signed cursors for one immutable persisted result set. */
final class ProductPatentSearchCursor {
    record Context(String resultSetId, String methodVersion, String subjectId, long taskVersion,
                   String inputRevision, String scopeSha, String sourceEpochSha, Instant expiresAt) { }
    private record Stored(String resultSetId, long nextOrdinal, String subjectId, long taskVersion,
                          String inputRevision, String scopeSha, String sourceEpochSha,
                          String methodVersion, int pageSize, Instant expiresAt) { }
    private record Continuation(String id, Instant expiresAt) { }

    private final JdbcTemplate db;
    private final byte[] key;
    private final Duration maxAge;
    private final Clock clock;

    ProductPatentSearchCursor(JdbcTemplate db, byte[] key, Duration maxAge, Clock clock) {
        if (key == null || key.length < 32) throw new IllegalArgumentException("Cursor key requires 256 bits");
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative())
            throw new IllegalArgumentException("Positive cursor lifetime required");
        this.db = db;
        this.key = key.clone();
        this.maxAge = maxAge;
        this.clock = clock;
        db.queryForObject("SELECT COUNT(*) FROM con4_result_cursor", Integer.class);
    }

    long resolve(String token, Context expected, int pageSize) {
        requirePageSize(pageSize);
        if (token == null || token.isBlank()) return 0;
        String[] sections = token.split("\\.", -1);
        if (sections.length != 2) throw conflict();
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(sections[0]);
            signature = Base64.getUrlDecoder().decode(sections[1]);
        } catch (IllegalArgumentException invalid) {
            throw conflict();
        }
        if (!MessageDigest.isEqual(signature, sign(payload))) throw conflict();
        String[] values = new String(payload, StandardCharsets.UTF_8).split("\\u0000", -1);
        if (values.length != 6) throw conflict();
        long nextOrdinal;
        long expiresEpoch;
        int encodedPageSize;
        try {
            encodedPageSize = Integer.parseInt(values[3]);
            nextOrdinal = Long.parseLong(values[4]);
            expiresEpoch = Long.parseLong(values[5]);
        } catch (NumberFormatException invalid) {
            throw conflict();
        }
        if (!values[1].equals(expected.resultSetId()) || !values[2].equals(expected.methodVersion())
                || encodedPageSize != pageSize || nextOrdinal < 1) throw conflict();
        if (expiresEpoch <= clock.instant().getEpochSecond()) throw gone();
        List<Stored> rows = db.query("SELECT result_set_id,next_ordinal,subject_id,task_version,"
                        + "input_revision,scope_sha,source_epoch_sha,method_version,page_size,expires_at "
                        + "FROM con4_result_cursor WHERE cursor_id=?", (rs, row) -> new Stored(
                                rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4),
                                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                                rs.getInt(9), rs.getTimestamp(10).toInstant()), values[0]);
        if (rows.size() != 1) throw conflict();
        Stored stored = rows.getFirst();
        if (!matches(stored, expected, encodedPageSize, nextOrdinal)) throw conflict();
        if (!stored.expiresAt().isAfter(clock.instant())) throw gone();
        return nextOrdinal;
    }

    String issue(Context context, int pageSize, long nextOrdinal) {
        requirePageSize(pageSize);
        if (nextOrdinal < 1) throw new IllegalArgumentException("Positive next ordinal required");
        Instant expiresAt = min(context.expiresAt(), clock.instant().plus(maxAge));
        if (!expiresAt.isAfter(clock.instant())) throw gone();
        List<Continuation> existing = db.query("SELECT cursor_id,expires_at FROM con4_result_cursor "
                        + "WHERE result_set_id=? AND method_version=? AND subject_id=? AND task_version=? "
                        + "AND input_revision=? AND scope_sha=? AND source_epoch_sha=? AND page_size=? "
                        + "AND next_ordinal=? AND expires_at>? AND expires_at<=? "
                        + "ORDER BY expires_at DESC,cursor_id FETCH FIRST 1 ROWS ONLY",
                (rs, row) -> new Continuation(rs.getString(1), rs.getTimestamp(2).toInstant()),
                context.resultSetId(), context.methodVersion(), context.subjectId(), context.taskVersion(),
                context.inputRevision(), context.scopeSha(), context.sourceEpochSha(), pageSize, nextOrdinal,
                java.sql.Timestamp.from(Instant.ofEpochSecond(clock.instant().getEpochSecond() + 1)),
                java.sql.Timestamp.from(expiresAt));
        if (!existing.isEmpty() && existing.getFirst().expiresAt().getEpochSecond()
                > clock.instant().getEpochSecond())
            return encode(existing.getFirst().id(), context, pageSize, nextOrdinal,
                    existing.getFirst().expiresAt());
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO con4_result_cursor(cursor_id,result_set_id,next_ordinal,subject_id,"
                        + "task_version,input_revision,scope_sha,source_epoch_sha,expires_at,"
                        + "method_version,page_size) VALUES(?,?,?,?,?,?,?,?,?,?,?)", id,
                context.resultSetId(), nextOrdinal, context.subjectId(), context.taskVersion(),
                context.inputRevision(), context.scopeSha(), context.sourceEpochSha(),
                java.sql.Timestamp.from(expiresAt), context.methodVersion(), pageSize);
        return encode(id, context, pageSize, nextOrdinal, expiresAt);
    }

    private String encode(String id, Context context, int pageSize, long nextOrdinal, Instant expiresAt) {
        byte[] payload = String.join("\u0000", id, context.resultSetId(), context.methodVersion(),
                Integer.toString(pageSize), Long.toString(nextOrdinal),
                Long.toString(expiresAt.getEpochSecond())).getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    private boolean matches(Stored stored, Context expected, int pageSize, long nextOrdinal) {
        return stored.resultSetId().equals(expected.resultSetId())
                && stored.nextOrdinal() == nextOrdinal
                && stored.subjectId().equals(expected.subjectId())
                && stored.taskVersion() == expected.taskVersion()
                && stored.inputRevision().equals(expected.inputRevision())
                && stored.scopeSha().equals(expected.scopeSha())
                && stored.sourceEpochSha().equals(expected.sourceEpochSha())
                && stored.methodVersion().equals(expected.methodVersion())
                && stored.pageSize() == pageSize;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception impossible) {
            throw new IllegalStateException("Cursor signature unavailable", impossible);
        }
    }

    private static void requirePageSize(int pageSize) {
        if (pageSize != 10 && pageSize != 20 && pageSize != 50)
            throw new IllegalArgumentException("Page size must be 10, 20, or 50");
    }

    private static Instant min(Instant left, Instant right) { return left.isBefore(right) ? left : right; }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Cursor does not match result set");
    }
    private static ResponseStatusException gone() {
        return new ResponseStatusException(HttpStatus.GONE, "Cursor expired");
    }
}
