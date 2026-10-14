package cn.xinji.agentplatform.adjudication.casework;

import java.time.Instant;
import java.util.*;
import static cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import static cn.xinji.agentplatform.adjudication.casework.CaseWorkIntegrity.*;

/** Domain-owned working data only. Identity, current source rights and atomic CAS belong to application ports. */
public final class AdjudicationCaseWork {
    private AdjudicationCaseWork() { }

    public static State open(CaseInput input, String actor, Instant now) {
        text(actor); Objects.requireNonNull(now);
        return new State(1, 1, input, List.of(), List.of(), List.of(), List.of(), List.of(), Set.of(actor),
                List.of(new Audit(1, "OPEN_WORK", actor, input.id(), now)));
    }
    /** An organization-run audit does not change facts or existing report inputs. */
    public static State recordMaterialRun(State state,long expectedVersion,String runId,String actor,Instant now) {
        check(state,expectedVersion,actor,now);text(runId);
        return next(state,state.materials(),state.claim(),state.findings(),state.citations(),state.reports(),false,
                "ORGANIZE_MATERIALS",runId,actor,now);
    }
    public static State addMaterial(State state, long expectedVersion, Material material, String actor, Instant now) {
        check(state, expectedVersion, actor, now); sameCase(state, material.caseId()); sourceConsistent(state, material.source());
        if (state.materials().stream().anyMatch(m -> m.id().equals(material.id()))) throw invalid("MATERIAL_ID_ALREADY_EXISTS");
        if (material.supersedesMaterialId() != null) {
            text(material.supersedesMaterialId());
            var previous = state.activeMaterials().stream().filter(m -> m.id().equals(material.supersedesMaterialId())).findFirst()
                    .orElseThrow(() -> invalid("SUPERSEDED_MATERIAL_NOT_CURRENT"));
            if (previous.side() != material.side() || !previous.submitter().equals(material.submitter())) throw invalid("MATERIAL_SUBMISSION_LINEAGE_CONFLICT");
        }
        return next(state, append(state.materials(), material), state.claim(), state.findings(), state.citations(), state.reports(),
                true, "ADD_MATERIAL", material.id(), actor, now);
    }
    public static State selectClaim(State state, long expectedVersion, Claim claim, String actor, Instant now) {
        check(state, expectedVersion, actor, now); sameCase(state, claim.caseId()); sourceConsistent(state, claim.source());
        if (claim.revision() != (state.claim() == null ? 1 : state.claim().revision() + 1)) throw invalid("CLAIM_REVISION_CONFLICT");
        return next(state, state.materials(), claim, state.findings(), state.citations(), state.reports(),
                true, "SELECT_CLAIM", claim.claimNumber(), actor, now);
    }
    public static State addFinding(State state, long expectedVersion, Finding finding, String actor, Instant now) {
        check(state, expectedVersion, actor, now); sameCase(state, finding.caseId());
        if (state.claim() == null || state.claim().revision() != finding.claimRevision()) throw invalid("CLAIM_REVISION_CONFLICT");
        if (state.findings().stream().anyMatch(f -> f.id().equals(finding.id()))) throw invalid("FINDING_ID_ALREADY_EXISTS");
        if (state.claim().elements().stream().noneMatch(e -> e.id().equals(finding.elementId()))) throw invalid("ELEMENT_NOT_FOUND");
        var material = state.activeMaterials().stream().filter(m -> m.id().equals(finding.materialId())).findFirst()
                .orElseThrow(() -> invalid("MATERIAL_NOT_FOUND"));
        if (!material.readable()) throw invalid("MATERIAL_NOT_READABLE");
        if (!material.content().contains(finding.exactExcerpt())) throw invalid("EXCERPT_NOT_IN_MATERIAL");
        return next(state, state.materials(), state.claim(), append(state.findings(), finding), state.citations(), state.reports(),
                true, "ADD_HUMAN_CORRESPONDENCE", finding.id(), actor, now);
    }
    public static State addCitation(State state, long expectedVersion, Citation citation, String actor, Instant now) {
        check(state, expectedVersion, actor, now); sameCase(state, citation.caseId()); sourceConsistent(state, citation.source());
        if (state.citations().stream().anyMatch(c -> c.id().equals(citation.id()))) throw invalid("CITATION_ID_ALREADY_EXISTS");
        if (!Objects.equals(state.caseInput().region(), citation.region())
                || !Objects.equals(state.caseInput().procedure(), citation.procedure())
                || !Objects.equals(state.caseInput().relevantDate(), citation.relevantDate())) throw invalid("CITATION_CONTEXT_MISMATCH");
        return next(state, state.materials(), state.claim(), state.findings(), append(state.citations(), citation), state.reports(),
                true, "SELECT_LEGAL_ORIGINAL", citation.id(), actor, now);
    }

    public static State generateReport(State state, long expectedVersion, Policy policy, String actor, Instant now) {
        check(state, expectedVersion, actor, now); Objects.requireNonNull(policy);
        var diagnostics = new ArrayList<Diagnostic>();
        if (state.caseInput().issues().isEmpty()) diagnostics.add(gap("ISSUES_MISSING", state.caseInput().id()));
        if (blank(state.caseInput().region()) || blank(state.caseInput().procedure()) || state.caseInput().relevantDate() == null)
            diagnostics.add(gap("LEGAL_CONTEXT_MISSING", state.caseInput().id()));
        for (var side : Side.values()) if (state.activeMaterials().stream().noneMatch(m -> m.side() == side))
            diagnostics.add(gap("PARTY_MATERIAL_MISSING", side.name()));
        for (var material : state.activeMaterials()) if (!material.readable()) diagnostics.add(gap("MATERIAL_UNREADABLE", material.id()));
        if (state.claim() == null) diagnostics.add(gap("SELECTED_CLAIM_MISSING", state.caseInput().id()));
        var analysis = new StringBuilder();
        if (state.claim() != null) for (var element : state.claim().elements()) {
            var matching = state.findings().stream().filter(f -> f.claimRevision() == state.claim().revision()
                    && f.elementId().equals(element.id())
                    && state.activeMaterials().stream().anyMatch(m -> m.id().equals(f.materialId()) && m.readable())).toList();
            boolean support = matching.stream().anyMatch(f -> f.relation() == Relation.SUPPORT);
            boolean opposing = matching.stream().anyMatch(f -> f.relation() == Relation.OPPOSING);
            if (!support && !opposing) diagnostics.add(gap("ELEMENT_SUPPORT_MISSING", element.id()));
            if (opposing) diagnostics.add(new Diagnostic(support ? "ELEMENT_CONFLICT" : "ELEMENT_OPPOSING", element.id(), support || policy.opposingRequiresResolution()));
            if (matching.stream().anyMatch(f -> f.relation() == Relation.UNCERTAIN)) diagnostics.add(gap("ELEMENT_UNCERTAIN", element.id()));
            analysis.append(element.id()).append(": ").append(element.exactText()).append("\n");
            for (var finding : matching) analysis.append(finding.relation()).append(" / ").append(finding.materialId())
                    .append(" / ").append(finding.exactExcerpt()).append(" / 人工说明：").append(finding.reason()).append("\n");
        }
        if (state.citations().isEmpty()) diagnostics.add(gap("LEGAL_SELECTION_MISSING", state.caseInput().id()));
        diagnostics.add(new Diagnostic("OFFICIAL_STATUS_NOT_VERIFIED", state.caseInput().id(), false));
        diagnostics.add(new Diagnostic("STABILITY_AND_EQUIVALENCE_NOT_ANALYSED", state.caseInput().id(), false));
        var sections = new LinkedHashMap<String, Section>();
        var input = state.caseInput();
        sections.put("case", new Section("case", input.name() + "\n申请人：" + input.applicant() + "\n被申请人：" + input.respondent()
                + "\n提出争点（非认定事实）：" + String.join("；", input.issues()), List.of(input.source())));
        String materialText = state.activeMaterials().stream().map(m -> m.id() + " / " + m.side() + " / " + m.submitter()
                + " / 主张用途：" + m.assertedPurpose() + "\n" + (m.readable() ? m.content() : "材料不可读；未展示正文")).reduce((a, b) -> a + "\n" + b).orElse("尚无材料");
        sections.put("materials", new Section("materials", materialText, state.activeMaterials().stream().filter(Material::readable).map(Material::source).toList()));
        sections.put("claim", new Section("claim", state.claim() == null ? "尚未人工选择权项" : state.claim().patentReference()
                + " / 权项" + state.claim().claimNumber() + " / v" + state.claim().revision() + "\n" + state.claim().originalText()
                + "\n产品：" + state.claim().selectedProduct() + "\n选择说明：" + state.claim().confirmationReason(),
                state.claim() == null ? List.of() : List.of(state.claim().source())));
        sections.put("analysis", new Section("analysis", analysis.isEmpty() ? "尚无逐要素对应输入" : analysis.toString(),
                state.activeMaterials().stream().filter(Material::readable).map(Material::source).toList()));
        sections.put("law", new Section("law", state.citations().stream().map(c -> c.id() + " / " + c.source().version()
                + " / " + c.source().locator() + "\n原文：" + c.originalText() + "\n人工解释：" + c.explanation()
                + "\n选择理由：" + c.selectionReason() + " / " + c.region() + " / " + c.procedure() + " / " + c.relevantDate())
                .reduce((a, b) -> a + "\n" + b).orElse("尚未人工选择法律原文"), state.citations().stream().map(Citation::source).toList()));
        sections.put("limitations", new Section("limitations", "本报告用于案件承办与复核。材料、主张和技术特征对应须结合完整证据审查；"
                + "权利状态、稳定性及等同判断须逐项核实。复核锁定报告版本；裁决签发由有权机关依程序确认。", List.of()));
        var ordered = policy.sectionOrder().stream().map(sections::get).toList();
        if (ordered.stream().mapToLong(s -> s.content().length()).sum() > policy.maxTextLength()) throw invalid("REPORT_SIZE_LIMIT");
        var contributors = new HashSet<>(state.inputContributors()); contributors.add(actor);
        var report = new Report(state.reports().size() + 1L, state.inputVersion(), policy.version(), actor, contributors,
                ordered, diagnostics, digest(state.inputVersion(), policy.version(), ordered, diagnostics), ReviewState.DRAFT, List.of());
        return next(state, state.materials(), state.claim(), state.findings(), state.citations(), append(state.reports(), report),
                false, "GENERATE_AUXILIARY_REPORT", Long.toString(report.revision()), actor, now);
    }
    /** Human changes produce a new candidate; original report and review history are immutable. */
    public static State reviseReport(State state, long expectedVersion, long baseRevision, String baseSha,
                                     String sectionId, String humanContent, String reason, Policy policy,
                                     String actor, Instant now) {
        check(state, expectedVersion, actor, now); text(humanContent); text(reason); Objects.requireNonNull(policy);
        var base = currentReport(state, baseRevision, baseSha);
        if (!base.policyVersion().equals(policy.version())) throw invalid("REPORT_POLICY_CONFLICT");
        if ("limitations".equals(sectionId)) throw invalid("AUXILIARY_BOUNDARY_NOT_EDITABLE");
        if (base.sections().stream().noneMatch(s -> s.id().equals(sectionId))) throw invalid("REPORT_SECTION_NOT_FOUND");
        var sections = base.sections().stream().map(s -> s.id().equals(sectionId)
                ? new Section(s.id(), "人工修订（" + actor + "；理由：" + reason + "）：\n" + humanContent, s.sources()) : s).toList();
        if (sections.stream().mapToLong(s -> s.content().length()).sum() > policy.maxTextLength()) throw invalid("REPORT_SIZE_LIMIT");
        var contributors = new HashSet<>(base.contributors()); contributors.add(actor);
        var revised = new Report(state.reports().size() + 1L, state.inputVersion(), policy.version(), actor, contributors,
                sections, base.diagnostics(), digest(state.inputVersion(), policy.version(), sections, base.diagnostics()),
                ReviewState.DRAFT, List.of());
        return next(state, state.materials(), state.claim(), state.findings(), state.citations(), append(state.reports(), revised),
                false, "REVISE_AUXILIARY_REPORT", Long.toString(revised.revision()), actor, now);
    }
    public static State submit(State state, long expectedVersion, long reportRevision, String reportSha, String actor, Instant now) {
        check(state, expectedVersion, actor, now); var report = currentReport(state, reportRevision, reportSha);
        if (!report.complete()) throw invalid("REPORT_INCOMPLETE");
        if (report.reviewState() != ReviewState.DRAFT) throw invalid("REPORT_NOT_DRAFT");
        var contributors = new HashSet<>(report.contributors()); contributors.add(actor);
        var submitted = new Report(report.revision(), report.inputVersion(), report.policyVersion(), report.author(), contributors,
                report.sections(), report.diagnostics(), report.contentSha256(), ReviewState.SUBMITTED, report.reviews());
        return replaceReport(state, submitted, "SUBMIT_REPORT", actor, now);
    }
    public static State review(State state, long expectedVersion, long reportRevision, String reportSha,
                               ReviewState outcome, String reason, String actor, Instant now) {
        check(state, expectedVersion, actor, now); var report = currentReport(state, reportRevision, reportSha);
        if (report.reviewState() != ReviewState.SUBMITTED) throw invalid("REPORT_NOT_SUBMITTED");
        if (report.contributors().contains(actor)) throw invalid("INDEPENDENT_REVIEW_REQUIRED");
        var decision = new Review(actor, reason, outcome, report.contentSha256(), now);
        var reviewed = new Report(report.revision(), report.inputVersion(), report.policyVersion(), report.author(), report.contributors(),
                report.sections(), report.diagnostics(), report.contentSha256(), outcome, append(report.reviews(), decision));
        return replaceReport(state, reviewed, "REVIEW_AUXILIARY_REPORT", actor, now);
    }

    private static State replaceReport(State state, Report report, String action, String actor, Instant now) {
        var reports = new ArrayList<>(state.reports()); reports.set(Math.toIntExact(report.revision() - 1), report);
        return next(state, state.materials(), state.claim(), state.findings(), state.citations(), reports, false,
                action, Long.toString(report.revision()), actor, now);
    }
    private static Report currentReport(State state, long revision, String hash) {
        var report = state.report(revision);
        if (revision != state.reports().size() || state.stale(report)) throw invalid("REPORT_INPUT_OR_HEAD_STALE");
        if (!report.contentSha256().equals(hash)) throw invalid("REPORT_HASH_CONFLICT");
        return report;
    }
    private static State next(State state, List<Material> materials, Claim claim, List<Finding> findings,
                              List<Citation> citations, List<Report> reports, boolean inputChange,
                              String action, String objectId, String actor, Instant now) {
        var contributors = new HashSet<>(state.inputContributors()); if (inputChange) contributors.add(actor);
        return new State(state.version() + 1, state.inputVersion() + (inputChange ? 1 : 0), state.caseInput(), materials,
                (claim == null || claim == state.claim() ? state.claims() : append(state.claims(), claim)), findings, citations, reports, contributors,
                append(state.audits(), new Audit(state.version() + 1, action, actor, objectId, now)));
    }
    private static void check(State state, long expected, String actor, Instant now) {
        Objects.requireNonNull(state); text(actor); Objects.requireNonNull(now);
        if (state.version() != expected) throw invalid("WORK_VERSION_CONFLICT");
    }
    private static void sameCase(State state, String id) {
        if (!state.caseInput().id().equals(id)) throw invalid("CROSS_CASE_INPUT");
    }
    private static void sourceConsistent(State state, Source proposed) {
        var existing = new ArrayList<Source>(); existing.add(state.caseInput().source());
        state.materials().forEach(m -> existing.add(m.source()));
        state.claims().forEach(c -> existing.add(c.source()));
        state.citations().forEach(c -> existing.add(c.source()));
        if (existing.stream().anyMatch(s -> s.id().equals(proposed.id()) && s.version().equals(proposed.version())
                && s.locator().equals(proposed.locator())
                && (!s.sha256().equals(proposed.sha256()) || s.kind() != proposed.kind())))
            throw invalid("SOURCE_VERSION_CONTENT_CONFLICT");
    }
    private static Diagnostic gap(String code, String id) { return new Diagnostic(code, id, true); }
    private static <T> List<T> append(List<T> original, T item) {
        var result = new ArrayList<>(original); result.add(item); return List.copyOf(result);
    }
    public static String sha256(String content) { return CaseWorkIntegrity.sha256(content); }
}
