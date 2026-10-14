from pathlib import Path
r=Path.cwd(); e=r/'docs/evidence/activation-recovery-design-20261010'
for name in ['ProductSubjects.java','ProductIdentityController.java']:
 p=r/'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product'/name
 (e/(name+'.before-implementation62')).write_bytes(p.read_bytes())
p=r/'docs/08-总开发实现计划.md'; s=p.read_text(encoding='utf-8'); lines=s.splitlines()
for i,l in enumerate(lines):
 if l.startswith('| IMP-CON-NORMAL-ACTIVATION-RECOVERY-01 |'):
  ps=l.split('|'); ps[4]=' 进行中 '; ps[-2]=' 62D后完整治理已结束exit1仅既有TESTS_FAILING/STALE，47/41一致；自动接续限定源码/专测，正在实现；无运行迁移/发行/本人交付，原资源/源返回不变 '; lines[i]='|'.join(ps)
p.write_text('\n'.join(lines)+'\n',encoding='utf-8')
p=r/'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductSubjects.java'; s=p.read_text(encoding='utf-8'); s=s.replace('import org.springframework.jdbc.core.JdbcTemplate;','import org.springframework.jdbc.core.JdbcTemplate;\nimport org.springframework.dao.DataIntegrityViolationException;')
s=s.replace('    private record Subject(','''    public record Recovery(String subjectId, String requestId, long credentialVersion, Instant expiresAt,
                           String delivery, String oneTimeActivation) {
        @Override public String toString() { return "Recovery[subjectId=" + subjectId + ",delivery=" + delivery + "]"; }
    }
    private record Pending(String status, String kind, String password, String digest, Instant until, long version) { }
    private record Subject(''')
pos=s.index('    public void activate(')
code='''    public Recovery recoverActivation(String subjectId, long expectedVersion, String actor, String requestId) {
        uuid(subjectId); uuid(requestId);
        if (blank(actor) || expectedVersion < 1 || expectedVersion == Long.MAX_VALUE)
            throw new IllegalArgumentException("Valid recovery request required");
        try {
            return transactions.execute(tx -> {
                List<Pending> rows = db.query("SELECT status,subject_kind,password_hash,activation_sha,"
                                + "activation_expires_at,credential_version FROM id2_subject "
                                + "WHERE issuer=? AND subject_id=? FOR UPDATE", (rs, n) -> new Pending(
                                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), rs.getLong(6)),
                        issuer, subjectId);
                if (rows.size() != 1) throw new IllegalStateException("Recovery unavailable");
                requireRegistrar(subjectId, actor);
                Recovery prior = recoveryReceipt(subjectId, expectedVersion, actor, requestId);
                if (prior != null) return prior;
                Pending pending = rows.getFirst(); Instant now = clock.instant();
                if (!"HUMAN".equals(pending.kind()) || !"PENDING".equals(pending.status())
                        || pending.password() != null || pending.digest() == null || pending.until() == null
                        || pending.until().isAfter(now) || pending.version() != expectedVersion)
                    throw new IllegalStateException("Recovery unavailable");
                Instant until = now.plus(activationLifetime);
                byte[] secret = new byte[32]; random.nextBytes(secret);
                String activation = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
                int changed = db.update("UPDATE id2_subject SET activation_sha=?,activation_expires_at=?,"
                                + "credential_version=credential_version+1 WHERE issuer=? AND subject_id=? "
                                + "AND subject_kind='HUMAN' AND status='PENDING' AND password_hash IS NULL "
                                + "AND credential_version=? AND activation_expires_at<=?",
                        sha(activation), Timestamp.from(until), issuer, subjectId, expectedVersion, Timestamp.from(now));
                if (changed != 1) throw new IllegalStateException("Recovery changed");
                event(subjectId, "REISSUE", expectedVersion, expectedVersion + 1, actor, now);
                db.update("INSERT INTO id2_activation_recovery_receipt(issuer,actor_ref,request_id,subject_id,"
                                + "expected_version,result_version,activation_expires_at,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                        issuer, actor, requestId, subjectId, expectedVersion, expectedVersion + 1,
                        Timestamp.from(until), Timestamp.from(now));
                return new Recovery(subjectId, requestId, expectedVersion + 1, until, "FIRST_RESPONSE", activation);
            });
        } catch (DataIntegrityViolationException failedTransaction) {
            // execute has already rolled back: never continue a rollback-only transaction.
            Recovery prior = transactions.execute(tx -> {
                requireRegistrar(subjectId, actor);
                return recoveryReceipt(subjectId, expectedVersion, actor, requestId);
            });
            if (prior != null) return prior;
            throw failedTransaction;
        }
    }

    private void requireRegistrar(String subjectId, String actor) {
        List<String> registrars = db.query("SELECT actor_ref FROM id2_subject_event WHERE issuer=? "
                        + "AND subject_id=? AND operation='REGISTER' AND before_version=0 AND after_version=1",
                (rs, n) -> rs.getString(1), issuer, subjectId);
        Integer count = db.queryForObject("SELECT COUNT(*) FROM id2_subject_event WHERE issuer=? "
                + "AND subject_id=? AND operation='REGISTER'", Integer.class, issuer, subjectId);
        if (count == null || count != 1 || registrars.size() != 1 || !actor.equals(registrars.getFirst()))
            throw new IllegalStateException("Recovery unavailable");
    }

    private Recovery recoveryReceipt(String subjectId, long expected, String actor, String requestId) {
        List<Recovery> receipts = db.query("SELECT subject_id,expected_version,result_version,"
                        + "activation_expires_at FROM id2_activation_recovery_receipt "
                        + "WHERE issuer=? AND actor_ref=? AND request_id=?", (rs, n) -> {
                    if (!subjectId.equals(rs.getString(1)) || expected != rs.getLong(2))
                        throw new IllegalStateException("Recovery request conflict");
                    return new Recovery(subjectId, requestId, rs.getLong(3), rs.getTimestamp(4).toInstant(),
                            "NOT_REDELIVERED", null);
                }, issuer, actor, requestId);
        return receipts.isEmpty() ? null : receipts.getFirst();
    }

    private static void uuid(String value) {
        if (value == null || !value.equals(UUID.fromString(value).toString()))
            throw new IllegalArgumentException("Canonical identifier required");
    }

'''
s=s[:pos]+code+s[pos:]; p.write_text(s,encoding='utf-8')
p=r/'backend/apps/platform-api/src/main/java/cn/xinji/agentplatform/security/product/ProductIdentityController.java'; s=p.read_text(encoding='utf-8').replace('import com.fasterxml.jackson.databind.ObjectMapper;','import com.fasterxml.jackson.databind.ObjectMapper;\nimport com.fasterxml.jackson.core.JsonParser;\nimport com.fasterxml.jackson.databind.JsonNode;\nimport jakarta.servlet.http.HttpServletResponse;\nimport java.util.LinkedHashMap;\nimport java.util.Map;')
pos=s.index('    @PostMapping("/subjects/{id}/activate")')
code='''    @PostMapping("/subjects/{id}/activation-recovery")
    public Map<String, Object> recoverActivation(@PathVariable String id, @RequestBody String body,
            @RequestHeader("Idempotency-Key") String requestId, HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        String actor = maintenance.require(request, ProductMaintenanceProof.Role.SUBJECTS);
        JsonNode input;
        try {
            if (body == null || body.length() > 128) throw new IllegalArgumentException("Invalid recovery request");
            input = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(body);
        } catch (JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid recovery request"); }
        if (input == null || !input.isObject() || input.size() != 1
                || !input.has("expectedCredentialVersion") || !input.get("expectedCredentialVersion").isIntegralNumber()
                || !input.get("expectedCredentialVersion").canConvertToLong())
            throw new IllegalArgumentException("Invalid recovery request");
        var result = subjects.recoverActivation(id, input.get("expectedCredentialVersion").longValue(), actor, requestId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("subjectId", result.subjectId()); view.put("requestId", result.requestId());
        view.put("credentialVersion", result.credentialVersion()); view.put("expiresAt", result.expiresAt());
        view.put("delivery", result.delivery());
        if (result.oneTimeActivation() != null) view.put("oneTimeActivation", result.oneTimeActivation());
        return view;
    }

'''; s=s[:pos]+code+s[pos:]; p.write_text(s,encoding='utf-8')
assert not (r/'db/dm8/V107__product_activation_recovery.sql').exists()
(r/'db/dm8/V107__product_activation_recovery.sql').write_text('''ALTER TABLE id2_subject_event DROP CONSTRAINT ck_id2_subject_event_operation;
ALTER TABLE id2_subject_event ADD CONSTRAINT ck_id2_subject_event_operation
 CHECK (operation IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE','REISSUE'));
CREATE TABLE id2_activation_recovery_receipt (
 issuer VARCHAR(300) NOT NULL,
 actor_ref VARCHAR(200) NOT NULL,
 request_id VARCHAR(36) NOT NULL,
 subject_id VARCHAR(200) NOT NULL,
 expected_version BIGINT NOT NULL,
 result_version BIGINT NOT NULL,
 activation_expires_at TIMESTAMP NOT NULL,
 occurred_at TIMESTAMP NOT NULL,
 CONSTRAINT pk_id2_activation_recovery PRIMARY KEY (issuer,actor_ref,request_id),
 CONSTRAINT fk_id2_activation_subject FOREIGN KEY (issuer,subject_id) REFERENCES id2_subject(issuer,subject_id),
 CONSTRAINT ck_id2_activation_versions CHECK (expected_version >= 1 AND result_version=expected_version+1)
);
''',encoding='utf-8')
(r/'db/dm8/U107__product_activation_recovery.sql').write_text('''-- DM8 guarded inverse: never destroy a committed recovery result or event.
DECLARE
 receipt_count BIGINT;
 event_count BIGINT;
BEGIN
 SELECT COUNT(*) INTO receipt_count FROM id2_activation_recovery_receipt;
 SELECT COUNT(*) INTO event_count FROM id2_subject_event WHERE operation='REISSUE';
 IF receipt_count > 0 OR event_count > 0 THEN
  RAISE_APPLICATION_ERROR(-20001, 'Activation recovery history prohibits reverse migration');
 END IF;
END;
/
DROP TABLE id2_activation_recovery_receipt;
ALTER TABLE id2_subject_event DROP CONSTRAINT ck_id2_subject_event_operation;
ALTER TABLE id2_subject_event ADD CONSTRAINT ck_id2_subject_event_operation
 CHECK (operation IN ('REGISTER','ACTIVATE','DISABLE','SERVICE_REGISTER','SERVICE_DISABLE'));
''',encoding='utf-8')
print('LIMITED_PRODUCT_SOURCE_WRITTEN; NO_RUNTIME_DB_OPERATIONS')
