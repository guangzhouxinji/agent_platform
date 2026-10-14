package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;

/** Normal Task creation with a fresh-only, private human-scope witness; replay never repairs history. */
final class ProductHumanTaskCreation {
    private final JdbcTemplate main, authority;
    private final DataSourceTransactionManager manager;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProductTaskCreationService creation;
    private final JwtDecoder decoder;
    private final ProductConsultationAccess access;
    private final ProductGrantWatermark watermark;
    private final String issuer;
    private final ProductHumanCleanupScope.Policy policy;

    ProductHumanTaskCreation(JdbcTemplate main, JdbcTemplate authority, DataSourceTransactionManager manager,
            ObjectMapper mapper, Clock clock, ProductTaskCreationService creation, JwtDecoder decoder,
            ProductConsultationAccess access, ProductGrantWatermark watermark, String issuer,
            ProductHumanCleanupScope.Policy policy) {
        this.main = Objects.requireNonNull(main); this.authority = Objects.requireNonNull(authority);
        this.manager = Objects.requireNonNull(manager); this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock); this.creation = Objects.requireNonNull(creation);
        this.decoder = Objects.requireNonNull(decoder); this.access = Objects.requireNonNull(access);
        this.watermark = Objects.requireNonNull(watermark); this.issuer = Objects.requireNonNull(issuer);
        this.policy = Objects.requireNonNull(policy);
        creation.requireBinding(main.getDataSource(), authority.getDataSource());
        if (main.getDataSource() == null || main.getDataSource() == authority.getDataSource()
                || manager.getDataSource() != main.getDataSource() || access.mainDataSource() != main.getDataSource()
                || watermark.mainDataSource() != main.getDataSource()
                || watermark.independentDatabase().getDataSource() != authority.getDataSource())
            throw new IllegalArgumentException("Matching creation, authorization and cleanup databases required");
    }

    ProductTaskCreationService.Result create(Jwt authenticated, String requestId, ProductTaskCreationService.Input input) {
        if (authenticated == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant now = clock.instant();
        if (input == null || blank(input.organizationId()) || blank(requestId) || requestId.length() > 200
                || blank(input.retentionRef()) || input.retentionVersion() < 1
                || input.expiresAt() == null || !input.expiresAt().equals(input.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                || !now.isBefore(input.expiresAt())
                || input.expiresAt().isAfter(now.plus(Duration.ofDays(365))))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var guard = new ProductHumanCreationGuard(main, watermark, access, decoder, issuer, clock,
                authenticated.getTokenValue(), input.organizationId(), input.expiresAt());
        Jwt jwt = guard.requireReady();
        var witness = new Witness(requestId, input, policy, jwt.getIssuer().toString(), jwt.getSubject(), guard);
        try {
            var scope = new ProductHumanCleanupScope(main, authority, manager, mapper, clock, witness);
            var result = creation.createObserved(jwt, requestId, input, witness);
            if (witness.fact == null) {
                witness.requireCurrent(clock.instant());
                return result; // Original Task receipt only; never a statement that human scope is ready.
            }
            witness.consume(result);
            Instant expiry = input.expiresAt();
            Instant backup = now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plus(Duration.ofDays(30));
            if (backup.isAfter(expiry)) backup = expiry;
            var initialized = scope.initialize(result.taskId(), policy, jwt.getSubject(), expiry, backup, expiry);
            witness.requireCurrent(clock.instant());
            if (!initialized.taskId().equals(result.taskId()) || !initialized.organization().equals(result.organizationId())
                    || !initialized.policy().equals(policy)) throw unavailable();
            return result;
        } finally {
            witness.close();
        }
    }

    private final class Witness implements ProductTaskCreationService.CreationObserver, ProductHumanCleanupScope.InitializationAdmission {
        private final String requestId, issuer, subject;
        private final ProductHumanCreationGuard guard;
        private final ProductTaskCreationService.Input input;
        private final ProductHumanCleanupScope.Policy policy;
        private ProductTaskCreationService.CreationFact fact;
        private Long grantGeneration;
        private boolean consumed, admitted, closed;
        Witness(String requestId, ProductTaskCreationService.Input input, ProductHumanCleanupScope.Policy policy, String issuer, String subject, ProductHumanCreationGuard guard) {
            this.requestId = requestId; this.input = input; this.policy = policy; this.issuer = issuer; this.subject = subject; this.guard = guard;
        }
        @Override public void requireCurrent(Instant now) {
            if (closed) throw unavailable();
            guard.requireReady();
            if (grantGeneration != null) watermark.assertUnchanged(grantGeneration);
        }
        @Override public void requireOwnedCurrent(Instant now, ProductGrantWatermark.OwnedChange owned) {
            if (closed) throw unavailable();
            guard.requireOwned(now, owned);
        }
        @Override public void created(ProductTaskCreationService.CreationFact value) {
            requireCurrent(clock.instant());
            if (fact != null || consumed || admitted || value == null) throw unavailable();
            var task = value.task(); var command = value.creation();
            if (task == null || command == null || blank(value.grantId()) || blank(task.id()) || blank(task.scopeId())
                    || !Objects.equals(requestId, command.requestId()) || !digest(command.requestSha())
                    || !issuer.equals(command.issuer()) || !subject.equals(command.subject())
                    || !issuer.equals(task.ownerIssuer()) || !subject.equals(task.ownerSubject())
                    || !input.organizationId().equals(command.organization()) || !task.organization().equals(command.organization())
                    || !input.expiresAt().equals(task.expiresAt()) || !input.expiresAt().equals(command.expiresAt())
                    || !input.retentionRef().equals(command.retentionRef()) || input.retentionVersion() != command.retentionVersion()
                    || !digest(command.retentionEvidenceSha()) || !"AWAITING_GOAL".equals(task.state())
                    || task.version() != 1 || task.authorizationVersion() != 1 || task.scopeVersion() != 1)
                throw unavailable();
            fact = value;
            grantGeneration = watermark.currentGeneration();
            nativeFact();
            watermark.assertUnchanged(grantGeneration);
        }
        void consume(ProductTaskCreationService.Result result) {
            requireCurrent(clock.instant());
            if (consumed || fact == null || result == null) throw unavailable();
            // Consume before the first initialize attempt, including invalid initialization parameters.
            consumed = true;
            var task = fact.task();
            if (!task.id().equals(result.taskId()) || !task.organization().equals(result.organizationId())
                    || !task.dataScope().equals(result.dataScope()) || task.version() != result.version()
                    || !fact.grantId().equals(result.grantId())) throw unavailable();
            nativeFact();
        }
        @Override public void requireNewTaskWithoutHistory(String taskId, String organization,
                ProductHumanCleanupScope.Policy requestedPolicy, String operator, Instant now) {
            requireCurrent(now);
            if (!consumed || admitted || fact == null || !fact.task().id().equals(taskId)
                    || !fact.task().organization().equals(organization) || !policy.equals(requestedPolicy)
                    || !subject.equals(operator)) throw unavailable();
            admitted = true;
            // Called by CleanupScope while its real Task row lock is held.
            nativeFact();
            requireCurrent(clock.instant());
        }
        private void nativeFact() {
            var task = fact.task(); var command = fact.creation();
            var receipt = new JdbcProductTaskDirectory(main, clock).creationReceipt(requestId).orElseThrow(ProductHumanTaskCreation::unavailable);
            if (!receipt.current() || !task.equals(receipt.task()) || !command.requestSha().equals(receipt.requestSha())
                    || !fact.grantId().equals(receipt.grantId())) throw unavailable();
            Integer count = main.queryForObject("SELECT COUNT(*) FROM con3_task_create_receipt r JOIN con3_task t ON t.task_id=r.task_id "
                    + "WHERE r.request_id=? AND r.request_sha=? AND r.actor_issuer=? AND r.actor_subject=? AND r.organization_id=? "
                    + "AND r.task_id=? AND r.scope_id=? AND r.grant_id=? AND t.retention_ref=? AND t.retention_version=? AND t.retention_evidence_sha=?",
                    Integer.class, requestId, command.requestSha(), issuer, subject, task.organization(), task.id(), task.scopeId(), fact.grantId(),
                    command.retentionRef(), command.retentionVersion(), command.retentionEvidenceSha());
            Integer events = main.queryForObject("SELECT COUNT(*) FROM con3_event WHERE task_id=? AND operation='TASK_CREATED' "
                    + "AND actor_ref=? AND version=1 AND request_sha=?", Integer.class, task.id(), subject, command.requestSha());
            Integer readGrant = main.queryForObject("SELECT COUNT(*) FROM id2_grant g JOIN id2_current_epoch e "
                    + "ON e.current_grant_id=g.grant_id AND e.authorization_epoch=g.grant_generation "
                    + "AND e.issuer=g.issuer AND e.subject_id=g.subject_id AND e.organization=g.organization "
                    + "AND e.domain_code=g.domain_code AND e.resource_type=g.resource_type AND e.resource_id=g.resource_id "
                    + "AND e.action_code=g.action_code AND e.purpose=g.purpose WHERE g.grant_id=? AND g.issuer=? "
                    + "AND g.subject_id=? AND g.organization=? AND g.domain_code='CONSULTATION' AND g.task_scope=? "
                    + "AND g.data_scope=? AND g.resource_type='TASK' AND g.resource_id=? AND g.resource_version=? "
                    + "AND g.action_code='READ' AND g.purpose='TASK_WORK' AND g.status='ACTIVE' AND g.expires_at>=?",
                    Integer.class, fact.grantId(), issuer, subject, task.organization(), task.id(), task.dataScope(),
                    task.id(), task.authorizationVersion(), java.sql.Timestamp.from(task.expiresAt()));
            if (count == null || count != 1 || events == null || events != 1 || readGrant == null || readGrant != 1)
                throw unavailable();
        }
        void close() { closed = true; fact = null; }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Fresh human scope initialization unavailable"); }
}
