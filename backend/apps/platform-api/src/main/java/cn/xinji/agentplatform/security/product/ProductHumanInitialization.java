package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** One explicit maintenance attempt; no product route, persisted birth proof, or automatic retry. */
final class ProductHumanInitialization {
    static final String ACTION = "CREATE_TASK_AND_INITIALIZE_HUMAN_SCOPE";
    record Binding(String runId, String leaseId, String targetId, String primaryDatabaseId,
                   String authorityDatabaseId, String operator, String action, String approvalSha, Instant expiresAt) {
        Binding {
            if (blank(runId) || blank(leaseId) || blank(targetId) || blank(primaryDatabaseId)
                    || blank(authorityDatabaseId) || blank(operator) || !ACTION.equals(action)
                    || !digest(approvalSha) || expiresAt == null)
                throw new IllegalArgumentException("Exact maintenance binding required");
        }
    }
    interface CurrentLease {
        Binding binding();
        /** The adapter must authenticate the named targets, operator, fence, action and approval. */
        void requireCurrent(Binding expected, Instant now);
    }
    record Result(ProductTaskCreationService.Result task, ProductHumanCleanupScope.Snapshot scope) { }
    private final JdbcTemplate main, authority;
    private final DataSourceTransactionManager manager;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ProductTaskCreationService creation;
    private final JwtDecoder decoder;
    private final CurrentLease lease;
    private final Binding binding;
    private final AtomicBoolean attempted = new AtomicBoolean();

    ProductHumanInitialization(JdbcTemplate main, JdbcTemplate authority, DataSourceTransactionManager manager,
            ObjectMapper mapper, Clock clock, ProductTaskCreationService creation, JwtDecoder decoder, CurrentLease lease) {
        this.main = Objects.requireNonNull(main); this.authority = Objects.requireNonNull(authority);
        this.manager = Objects.requireNonNull(manager); this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock); this.creation = Objects.requireNonNull(creation);
        this.decoder = Objects.requireNonNull(decoder); this.lease = Objects.requireNonNull(lease);
        binding = Objects.requireNonNull(lease.binding());
        creation.requireBinding(main.getDataSource(), authority.getDataSource());
        if (main.getDataSource() == null || main.getDataSource() == authority.getDataSource()
                || manager.getDataSource() != main.getDataSource())
            throw new IllegalArgumentException("Matching main manager and independent authority required");
    }

    Result execute(String token, String requestId, ProductTaskCreationService.Input input,
            ProductHumanCleanupScope.Policy policy, Instant approvedAuditUntil, Instant lastBackupUntil, Instant validUntil) {
        if (!attempted.compareAndSet(false, true)) throw unavailable();
        Witness witness = null;
        try {
            requireLease(clock.instant());
            var jwt = decoder.decode(token);
            if (jwt == null || jwt.getIssuer() == null || blank(jwt.getSubject())) throw unavailable();
            Objects.requireNonNull(input); Objects.requireNonNull(policy);
            Objects.requireNonNull(approvedAuditUntil); Objects.requireNonNull(lastBackupUntil); Objects.requireNonNull(validUntil);
            witness = new Witness(requestId, input, policy, jwt.getIssuer().toString(), jwt.getSubject());
            var scope = new ProductHumanCleanupScope(main, authority, manager, mapper, clock, witness);
            var task = creation.createObserved(jwt, requestId, input, witness);
            witness.consume(task);
            var initialized = scope.initialize(task.taskId(), policy, binding.operator(), approvedAuditUntil, lastBackupUntil, validUntil);
            witness.requireCurrent(clock.instant());
            if (!initialized.taskId().equals(task.taskId()) || !initialized.organization().equals(task.organizationId())
                    || !initialized.policy().equals(policy)) throw unavailable();
            return new Result(task, initialized);
        } finally {
            if (witness != null) witness.close();
        }
    }

    private void requireLease(Instant at) {
        if (at == null || !at.isBefore(binding.expiresAt()) || !clock.instant().isBefore(binding.expiresAt())
                || !binding.equals(lease.binding())) throw unavailable();
        lease.requireCurrent(binding, at);
        // The external check may itself span expiration or a lease replacement.
        if (!clock.instant().isBefore(binding.expiresAt()) || !binding.equals(lease.binding())) throw unavailable();
    }

    private final class Witness implements ProductTaskCreationService.CreationObserver, ProductHumanCleanupScope.InitializationAdmission {
        private final String requestId, issuer, subject;
        private final ProductTaskCreationService.Input input;
        private final ProductHumanCleanupScope.Policy policy;
        private ProductTaskCreationService.CreationFact fact;
        private boolean consumed, admitted, closed;
        Witness(String requestId, ProductTaskCreationService.Input input, ProductHumanCleanupScope.Policy policy, String issuer, String subject) {
            this.requestId = requestId; this.input = input; this.policy = policy; this.issuer = issuer; this.subject = subject;
        }
        @Override public void requireCurrent(Instant now) {
            if (closed) throw unavailable();
            requireLease(now);
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
                    || !binding.operator().equals(operator)) throw unavailable();
            admitted = true;
            // Called by CleanupScope while its real Task row lock is held.
            nativeFact();
            requireCurrent(clock.instant());
        }
        private void nativeFact() {
            var task = fact.task(); var command = fact.creation();
            var receipt = new JdbcProductTaskDirectory(main, clock).creationReceipt(requestId).orElseThrow(ProductHumanInitialization::unavailable);
            if (!receipt.current() || !task.equals(receipt.task()) || !command.requestSha().equals(receipt.requestSha())
                    || !fact.grantId().equals(receipt.grantId())) throw unavailable();
            Integer count = main.queryForObject("SELECT COUNT(*) FROM con3_task_create_receipt r JOIN con3_task t ON t.task_id=r.task_id "
                    + "WHERE r.request_id=? AND r.request_sha=? AND r.actor_issuer=? AND r.actor_subject=? AND r.organization_id=? "
                    + "AND r.task_id=? AND r.scope_id=? AND r.grant_id=? AND t.retention_ref=? AND t.retention_version=? AND t.retention_evidence_sha=?",
                    Integer.class, requestId, command.requestSha(), issuer, subject, task.organization(), task.id(), task.scopeId(), fact.grantId(),
                    command.retentionRef(), command.retentionVersion(), command.retentionEvidenceSha());
            Integer events = main.queryForObject("SELECT COUNT(*) FROM con3_event WHERE task_id=? AND operation='TASK_CREATED' "
                    + "AND actor_ref=? AND version=1 AND request_sha=?", Integer.class, task.id(), subject, command.requestSha());
            if (count == null || count != 1 || events == null || events != 1) throw unavailable();
        }
        void close() { closed = true; fact = null; }
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean digest(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Managed human initialization evidence unavailable"); }
}
