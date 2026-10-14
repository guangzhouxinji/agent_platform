package cn.xinji.agentplatform.security.product;

import cn.xinji.agentplatform.consultation.patenttask.authorization.ProductTaskDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact product maintenance command. Never grants QUERY, DISPLAY or model use as a side effect. */
public final class ProductComparisonScopeGrants {
    public record Input(String taskId, String memberSubject, long taskVersion, long sourceRowVersion,
                        long sourceEpoch, String scopeId, long scopeVersion, Set<String> requiredFields,
                        Instant expiresAt, long expectedEpoch) { }
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final ProductTaskDirectory tasks;
    private final ProductGrants grants;
    private final ProductGrantWatermark grantWatermark;
    private final ProductSourceWatermark sourceWatermark;
    private final ProductSourceAvailability availability;
    private final ProductMaintenanceProof maintenance;
    private final Clock clock;
    private final String issuer;

    public ProductComparisonScopeGrants(JdbcTemplate db, TransactionTemplate transactions,
            ProductTaskDirectory tasks, ProductGrants grants, ProductGrantWatermark grantWatermark,
            ProductSourceWatermark sourceWatermark, ProductSourceAvailability availability,
            ProductMaintenanceProof maintenance, Clock clock, String issuer) {
        this.db=db; this.transactions=transactions; this.tasks=tasks; this.grants=grants;
        this.grantWatermark=grantWatermark; this.sourceWatermark=sourceWatermark;
        this.availability=availability; this.maintenance=maintenance; this.clock=clock; this.issuer=issuer;
    }
    public ProductGrants.Receipt authorize(String sourceId, Input input, String requestId, HttpServletRequest request) {
        return authorizePurpose(sourceId,input,requestId,request,false);
    }
    public ProductGrants.Receipt authorizeModelInput(String sourceId,Input input,String requestId,HttpServletRequest request) {
        return authorizePurpose(sourceId,input,requestId,request,true);
    }
    private ProductGrants.Receipt authorizePurpose(String sourceId,Input input,String requestId,HttpServletRequest request,boolean model) {
        String actor=maintenance.require(request, ProductMaintenanceProof.Role.SOURCES);
        String organization=maintenance.requireOrganization(request, ProductMaintenanceProof.Role.SOURCES);
        if (!actor.equals(maintenance.require(request, ProductMaintenanceProof.Role.GRANTS))
                || !organization.equals(maintenance.requireOrganization(request, ProductMaintenanceProof.Role.GRANTS)))
            throw new SecurityException("Maintenance principal or organization changed");
        return authorizeVerifiedPurpose(sourceId,input,requestId,actor,organization,model);
    }
    ProductGrants.Receipt authorizeVerified(String sourceId, Input input, String requestId,
            String actor, String organization) {
        return authorizeVerifiedPurpose(sourceId,input,requestId,actor,organization,false);
    }
    ProductGrants.Receipt authorizeModelInputVerified(String sourceId,Input input,String requestId,String actor,String organization) {
        return authorizeVerifiedPurpose(sourceId,input,requestId,actor,organization,true);
    }
    private ProductGrants.Receipt authorizeVerifiedPurpose(String sourceId,Input input,String requestId,String actor,String organization,boolean model) {
        String use=model?"MODEL":"COMPARISON",purpose=model?"MODEL_INPUT":"COMPARISON";
        if (blank(sourceId) || blank(actor) || blank(organization) || blank(issuer) || input==null
                || blank(input.taskId()) || blank(input.memberSubject()) || blank(input.scopeId())
                || input.taskVersion()<1 || input.sourceRowVersion()<1 || input.sourceEpoch()<1
                || input.scopeVersion()<1 || input.expectedEpoch()<0 || input.expiresAt()==null
                || !input.expiresAt().isAfter(clock.instant()) || input.requiredFields()==null
                || input.requiredFields().isEmpty() || input.requiredFields().stream()
                        .anyMatch(field -> field==null || !Set.of("TITLE","CLAIMS").contains(field))
                || requestId==null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,179}"))
            throw new IllegalArgumentException("Exact comparison scope request required");
        Set<String> fields=Set.copyOf(input.requiredFields());
        Instant until=input.expiresAt().truncatedTo(ChronoUnit.MILLIS);
        if (!until.isAfter(clock.instant())) throw new IllegalArgumentException("Grant expiry is too close");
        var source=availability.requireFields(sourceId,fields,use);
        if (source.epoch()!=input.sourceEpoch() || source.rowVersion()!=input.sourceRowVersion())
            throw new IllegalStateException("Source comparison version changed");
        String sha=digest(issuer,sourceId,input,requestId,actor,organization,fields,until);if(model)sha=ProductComparisonJson.hash("MODEL_INPUT:"+sha);
        final String requestSha=sha;
        ProductGrants.Receipt result=grantWatermark.change(() -> transactions.execute(status -> {
            if (db.query("SELECT task_id FROM con3_task WHERE task_id=? FOR UPDATE",
                    (rs,row)->rs.getString(1),input.taskId()).size()!=1) throw unavailable();
            var task=tasks.find(input.taskId()).orElseThrow(ProductComparisonScopeGrants::unavailable);
            if (!organization.equals(task.organization()) || task.version()!=input.taskVersion()
                    || !"IN_PROGRESS".equals(task.state()) || task.expiresAt().isBefore(until)) throw unavailable();
            List<String> members=db.query("SELECT status FROM id2_subject WHERE issuer=? AND subject_id=? "
                    + "AND subject_kind='HUMAN' AND password_hash IS NOT NULL FOR UPDATE",
                    (rs,row)->rs.getString(1),issuer,input.memberSubject());
            if (members.size()!=1 || !"ACTIVE".equals(members.getFirst())) throw unavailable();
            var current=availability.requireFields(sourceId,fields,use);
            if (!source.equals(current)) throw unavailable();
            Integer scope=db.queryForObject("SELECT COUNT(*) FROM con4_task_source_scope s "
                    + "JOIN con4_task_source_current c ON c.task_id=s.task_id AND c.source_id=s.source_id "
                    + "AND c.scope_id=s.scope_id AND c.scope_version=s.scope_version "
                    + "JOIN con4_source_product_decision d ON d.decision_id=s.decision_id "
                    + "WHERE s.task_id=? AND s.source_id=? AND s.scope_id=? AND s.scope_version=? "
                    + "AND s.organization_id=? AND s.task_version=? AND s.contract_version=? AND s.decision_id=? "
                    + "AND s.source_epoch=? AND s.source_row_version=? AND s.status='ACTIVE' AND c.status='ACTIVE' "
                    + "AND s.expires_at>=? AND d.organization_id=? AND d.task_id=? "
                    + "AND d.rights_evidence_status<>'WITHDRAWN'",Integer.class,input.taskId(),sourceId,
                    input.scopeId(),input.scopeVersion(),organization,input.taskVersion(),source.contractVersion(),
                    source.decisionId(),source.epoch(),source.rowVersion(),Timestamp.from(until),organization,input.taskId());
            if (scope==null || scope!=1) throw unavailable();
            for (String field:fields) {
                Integer uses=db.queryForObject("SELECT COUNT(*) FROM con4_source_field_use "
                        + "WHERE decision_id=? AND field_code=? AND use_code=? AND valid_until>=?",
                        Integer.class,source.decisionId(),field,use,Timestamp.from(until));
                if (uses==null || uses!=1) throw unavailable();
            }
            var issued=grants.issueSourceScope(new ProductGrants.Scope(issuer,input.memberSubject(),organization,
                    "CONSULTATION",input.taskId(),input.scopeId()+":"+input.scopeVersion(),"SOURCE_SCOPE",
                    input.scopeId(),input.scopeVersion(),"COMPARE",purpose),until,input.expectedEpoch(),
                    actor,requestId,requestSha,sourceWatermark);
            sourceWatermark.assertUnchanged(sourceId,source.epoch());
            return issued;
        }));
        sourceWatermark.assertUnchanged(sourceId,source.epoch()); grantWatermark.assertCurrent();
        return result;
    }
    private static String digest(String issuer, String sourceId, Input input, String requestId, String actor,
            String organization, Set<String> fields, Instant until) {
        try {
            var hash=MessageDigest.getInstance("SHA-256");
            List<String> parts=new ArrayList<>(List.of("COMPARISON_GRANT_V1",issuer,actor,organization,requestId,sourceId,
                    input.taskId(),input.memberSubject(),Long.toString(input.taskVersion()),Long.toString(input.sourceRowVersion()),
                    Long.toString(input.sourceEpoch()),input.scopeId(),Long.toString(input.scopeVersion()),
                    until.toString(),Long.toString(input.expectedEpoch()),Integer.toString(fields.size())));
            parts.addAll(new TreeSet<>(fields));
            for (String part:parts) hash.update((part.length()+":"+part).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean blank(String value) { return value==null || value.isBlank(); }
    private static IllegalStateException unavailable() { return new IllegalStateException("Current comparison scope authority unavailable"); }
}
