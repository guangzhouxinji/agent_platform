package cn.xinji.agentplatform.consultation.prompt.application;

import cn.xinji.agentplatform.consultation.prompt.domain.PromptLifecycle;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import cn.xinji.agentplatform.consultation.prompt.domain.TemplateRenderer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Contracts for trusted adapters; this class supplies no identity or policy defaults. */
public final class PromptPorts {
    private PromptPorts() {}
    public enum Permission {
        READ_METADATA, READ_SYSTEM_BODY, WRITE_DRAFT, PREVIEW_TEST, PUBLISH, DISABLE,
        EMERGENCY_REVOKE, ARCHIVE, DISPLAY_GUIDE, RESOLVE_FIXED_SYSTEM
    }
    public record Context(String verifiedSubject, String organizationRef, String resourceScopeRef,
                          Instant validUntil) {}
    public record Query(String exactId, String keyword, PromptRevision.Kind kind, String scenarioId,
                        PromptLifecycle.Status status, int page, int pageSize) {}
    public record Page(List<PromptRevision> records, long total, Instant asOf) {
        public Page { records = List.copyOf(records); }
    }
    public record Retention(String policyRef, String policySha256, Instant expiresAt, Instant auditExpiresAt) {}

    public interface Authorization {
        void requireCurrent(Context context, Permission action, PromptRevision.Reference revision, Instant now);
    }
    public interface Repository {
        PromptRevision exactRevision(PromptRevision.Reference reference);
        PromptLifecycle lockedLifecycle(PromptRevision.Reference reference);
        Page queryAuthorized(Context context, Query query);
        void compareAndSetLifecycle(PromptLifecycle previous, PromptLifecycle next);
    }
    public interface VariablePolicy {
        /** Authenticate the original policy bytes/digest and current scope before returning its definitions. */
        TemplateRenderer.Policy currentPolicy(Context context, PromptRevision revision, Instant now);
    }
    public interface VariableValues {
        Map<String, TemplateRenderer.Value> resolveAuthorized(Context context, PromptRevision revision,
                                                               Set<String> names, Instant now);
    }
    public interface Binding {
        PromptLifecycle.BindingProof currentFixedBinding(Context serviceIdentity,
                                                         String configurationRevisionRef,
                                                         PromptRevision.Reference revision, Instant now);
    }
    public interface Catalog {
        void requireCurrentAssociations(Context context, Set<String> scenarioIds, Set<String> intentIds, Instant now);
    }
    public interface ContentApproval {
        void requireExactApproval(Context context, PromptRevision revision, Instant now);
    }
    public interface RetentionPolicy {
        Retention requireCoveredPolicy(Context context, PromptRevision revision, Instant now);
    }
}
