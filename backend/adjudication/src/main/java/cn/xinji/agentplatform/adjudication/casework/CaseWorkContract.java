package cn.xinji.agentplatform.adjudication.casework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static cn.xinji.agentplatform.adjudication.casework.CaseWorkIntegrity.*;

/** Immutable adjudication working contract, separate from official case and authority state. */
public final class CaseWorkContract {
    private CaseWorkContract() { }
    public enum InputKind { PUBLIC, DEIDENTIFIED, USER_PROVIDED, PROJECT_AUTHORED }
    public enum Side { APPLICANT, RESPONDENT }
    public enum Relation { SUPPORT, OPPOSING, UNCERTAIN }
    public enum ReviewState { DRAFT, SUBMITTED, RETURNED, REVIEWED_NOT_OFFICIAL }

    public record Source(String id, String version, String locator, String sha256, InputKind kind) {
        public Source {
            text(id); text(version); text(locator); Objects.requireNonNull(kind);
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw invalid("SOURCE_HASH_REQUIRED");
        }
    }
    public record CaseInput(String id, String organization, String dataScope, String name,
                            String applicant, String respondent, List<String> issues,
                            String region, String procedure, LocalDate relevantDate, Source source) {
        public CaseInput {
            text(id); text(organization); text(dataScope); text(name); text(applicant); text(respondent);
            issues = List.copyOf(issues); issues.forEach(CaseWorkIntegrity::text); Objects.requireNonNull(source);
        }
    }
    public record Material(String id, String caseId, Side side, String submitter, String content,
                           String assertedPurpose, Source source, boolean readable, String supersedesMaterialId) {
        public Material {
            text(id); text(caseId); Objects.requireNonNull(side); text(submitter); text(assertedPurpose);
            Objects.requireNonNull(source); text(content); checkHash(source, content);
        }
    }
    public record Element(String id, String exactText) {
        public Element { text(id); text(exactText); }
    }
    public record Claim(String caseId, String patentReference, String claimNumber, long revision,
                        String originalText, Source source, List<Element> elements, String selectedProduct,
                        String confirmationReason) {
        public Claim {
            text(caseId); text(patentReference); text(claimNumber); positive(revision); text(originalText);
            Objects.requireNonNull(source); checkHash(source, originalText); text(selectedProduct); text(confirmationReason);
            elements = List.copyOf(elements);
            if (elements.isEmpty()) throw invalid("NECESSARY_ELEMENTS_REQUIRED");
            unique(elements.stream().map(Element::id).toList());
            for (var element : elements) if (!originalText.contains(element.exactText())) throw invalid("ELEMENT_NOT_IN_ORIGINAL");
        }
    }
    /** Human-recorded correspondence, not a legal finding or similarity-score inference. */
    public record Finding(String id, String caseId, long claimRevision, String elementId, String materialId,
                          Relation relation, String exactExcerpt, String reason) {
        public Finding {
            text(id); text(caseId); positive(claimRevision); text(elementId); text(materialId);
            Objects.requireNonNull(relation); text(exactExcerpt); text(reason);
        }
    }
    public record Citation(String id, String caseId, Source source, String originalText, String explanation,
                           String selectionReason, String region, String procedure, LocalDate relevantDate) {
        public Citation {
            text(id); text(caseId); Objects.requireNonNull(source); text(originalText); checkHash(source, originalText);
            text(explanation); text(selectionReason); text(region); text(procedure); Objects.requireNonNull(relevantDate);
        }
    }
    public record Policy(String version, List<String> sectionOrder, int maxTextLength, boolean opposingRequiresResolution) {
        public Policy(String version,List<String> sectionOrder,int maxTextLength){this(version,sectionOrder,maxTextLength,false);}
        private static final Set<String> SECTIONS = Set.of("case", "materials", "claim", "analysis", "law", "limitations");
        public Policy {
            text(version); sectionOrder = List.copyOf(sectionOrder); unique(sectionOrder);
            if (!Set.copyOf(sectionOrder).equals(SECTIONS) || maxTextLength < 1) throw invalid("INVALID_REPORT_POLICY");
        }
        public static Policy standard() {
            return new Policy("ADJ_CASE_REPORT/v2", List.of("case", "materials", "claim", "analysis", "law", "limitations"), 100_000);
        }
    }
    public record Diagnostic(String code, String objectId, boolean blocking) {
        public Diagnostic { text(code); text(objectId); }
    }
    public record Section(String id, String content, List<Source> sources) {
        public Section { text(id); text(content); sources = List.copyOf(sources); }
    }
    public record Review(String actor, String reason, ReviewState outcome, String reportSha256, Instant at) {
        public Review {
            text(actor); text(reason); text(reportSha256); Objects.requireNonNull(at);
            if (outcome != ReviewState.RETURNED && outcome != ReviewState.REVIEWED_NOT_OFFICIAL) throw invalid("INVALID_REVIEW_OUTCOME");
        }
    }
    public record Report(long revision, long inputVersion, String policyVersion, String author,
                         Set<String> contributors, List<Section> sections, List<Diagnostic> diagnostics,
                         String contentSha256, ReviewState reviewState, List<Review> reviews) {
        public Report {
            positive(revision); positive(inputVersion); text(policyVersion); text(author);
            contributors = Set.copyOf(contributors); sections = List.copyOf(sections);
            diagnostics = List.copyOf(diagnostics); reviews = List.copyOf(reviews); Objects.requireNonNull(reviewState);
            if (!contributors.contains(author)) throw invalid("REPORT_AUTHOR_NOT_BOUND");
            if (!digest(inputVersion, policyVersion, sections, diagnostics).equals(contentSha256)) throw invalid("REPORT_CONTENT_HASH_MISMATCH");
        }
        public boolean complete() { return diagnostics.stream().noneMatch(Diagnostic::blocking); }
    }
    public record Audit(long version, String action, String actor, String objectId, Instant at) {
        public Audit { positive(version); text(action); text(actor); text(objectId); Objects.requireNonNull(at); }
    }
    public record State(long version, long inputVersion, CaseInput caseInput, List<Material> materials, List<Claim> claims,
                        List<Finding> findings, List<Citation> citations, List<Report> reports,
                        Set<String> inputContributors, List<Audit> audits) {
        public State {
            positive(version); positive(inputVersion); Objects.requireNonNull(caseInput);
            materials = List.copyOf(materials); claims = List.copyOf(claims); findings = List.copyOf(findings); citations = List.copyOf(citations);
            reports = List.copyOf(reports); inputContributors = Set.copyOf(inputContributors); audits = List.copyOf(audits);
        }
        public Claim claim() { return claims.isEmpty() ? null : claims.getLast(); }
        public List<Material> activeMaterials() {
            var superseded = materials.stream().map(Material::supersedesMaterialId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            return materials.stream().filter(m -> !superseded.contains(m.id())).toList();
        }
        public Report report(long revision) {
            return reports.stream().filter(r -> r.revision() == revision).findFirst().orElseThrow(() -> invalid("REPORT_NOT_FOUND"));
        }
        public boolean stale(Report report) { return report.inputVersion() != inputVersion; }
    }

}
