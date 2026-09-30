package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/** Accountable first-task provisioning using the existing independent grant authority. */
public final class ProductInitialTaskService {
    public record Input(String organization, String name, String memberSubject, String retentionRef,
                        long retentionVersion, Instant expiresAt) { }
    public record Result(String taskId, String organization, String dataScope, long version, String grantId) { }

    private final ProductTaskDirectory directory;
    private final ProductGrants grants;
    private final ProductGrantWatermark watermark;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String issuer;
    private final ProductRetentionPolicy retention;

    public ProductInitialTaskService(ProductTaskDirectory directory, ProductGrants grants,
            ProductGrantWatermark watermark, TransactionTemplate transactions, ObjectMapper mapper,
            Clock clock, String issuer, ProductRetentionPolicy retention) {
        this.directory = directory;
        this.grants = grants;
        this.watermark = watermark;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
        this.issuer = issuer;
        this.retention = retention;
    }

    public Result create(Input input, String requestId, String maintainer, String maintainerOrganization) {
        if (input == null || blank(input.organization()) || blank(input.name())
                || blank(input.memberSubject()) || blank(input.retentionRef())
                || input.retentionVersion() != retention.version()
                || !input.retentionRef().equals(retention.reference()) || input.expiresAt() == null
                || !input.expiresAt().isAfter(clock.instant()) || blank(requestId)
                || requestId.length() > 200 || blank(maintainer)
                || !input.organization().equals(maintainerOrganization))
            throw new IllegalArgumentException("Exact deployment organization and retention policy required");
        if (input.expiresAt().isAfter(clock.instant().plus(retention.maxAge())))
            throw new IllegalArgumentException("Task expiry exceeds the deployed retention policy");
        String hash = digest(input, maintainer, requestId);
        watermark.assertCurrent();
        var prior = directory.receipt(requestId);
        if (prior.isPresent()) return replay(prior.get(), hash);
        try {
            return watermark.change(() -> transactions.execute(status -> {
                var concurrent = directory.receipt(requestId);
                if (concurrent.isPresent()) return replay(concurrent.get(), hash);
                var initial = new ProductTaskDirectory.Initial(input.organization(), input.name(), issuer,
                        input.memberSubject(), input.retentionRef(), input.retentionVersion(),
                        retention.evidenceSha256(),
                        input.expiresAt(), maintainer, requestId, hash);
                var task = directory.insertInitial(initial);
                var scope = new ProductGrants.Scope(issuer, input.memberSubject(), input.organization(),
                        "CONSULTATION", task.id(), task.dataScope(), "TASK", task.id(), task.authorizationVersion(),
                        "READ", "TASK_WORK");
                var grant = grants.issue(scope, input.expiresAt(), 0, maintainer, requestId, hash);
                directory.recordReceipt(initial, task, grant.grantId());
                return result(task, grant.grantId());
            }));
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalStateException("Initial organization or request already exists", conflict);
        }
    }

    private Result replay(ProductTaskDirectory.Receipt receipt, String hash) {
        if (!Objects.equals(hash, receipt.requestSha()) || !receipt.current() || receipt.task() == null
                || !receipt.task().expiresAt().isAfter(clock.instant()))
            throw new IllegalStateException("Initial request is changed or no longer current");
        return result(receipt.task(), receipt.grantId());
    }

    private Result result(ProductTaskDirectory.Task task, String grantId) {
        return new Result(task.id(), task.organization(), task.dataScope(), task.version(), grantId);
    }

    private String digest(Input input, String maintainer, String requestId) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(java.util.List.of(issuer, maintainer, requestId,
                    retention, input));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("Initial request cannot be digested", failure);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
