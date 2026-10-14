package cn.xinji.agentplatform.adjudication.casework;

import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.*;
import static cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** PROJECT_AUTHORED mechanical examples; no actual patent, law or adjudication conclusion is asserted. */
class AdjudicationCaseWorkTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String AUTHOR = "trusted-subject-a";
    private static final String REVIEWER = "trusted-subject-b";
    private static final String CLAIM_TEXT = "工程测试权项原文：外壳连接弹簧";
    private static Source source(String id, String content) {
        return new Source(id, "v1", "/original/" + id, sha256(content), InputKind.PROJECT_AUTHORED);
    }
    private static State opened() {
        return open(new CaseInput("case-a", "org-a", "scope-a", "原创工程输入（非真实案件）", "测试申请方", "测试答辩方",
                List.of("测试双方对连接结构存在争议"), "测试地域", "辅助工作", DATE, source("case-input", "工程测试输入")), AUTHOR, NOW);
    }
    private static Material material(String id, Side side, String content, boolean readable, String supersedes) {
        return new Material(id, "case-a", side, side.name() + "-submitter", content, "工程对应输入；非专业证据",
                source(id, content), readable, supersedes);
    }
    private static Claim claim(long revision) {
        return new Claim("case-a", "PROJECT_AUTHORED-NOT-A-PATENT", "1", revision, CLAIM_TEXT, source("claim", CLAIM_TEXT),
                List.of(new Element("e1", "外壳"), new Element("e2", "弹簧")), "测试产品结构", "人工选择测试输入");
    }
    private static Finding finding(String id, String element, String material, Relation relation, String excerpt) {
        return new Finding(id, "case-a", 1, element, material, relation, excerpt, "人工记录；非采信/侵权判断");
    }
    private static State withMaterials() {
        var state = opened();
        state = addMaterial(state, state.version(), material("m-a", Side.APPLICANT, "外壳连接弹簧", true, null), AUTHOR, NOW);
        return addMaterial(state, state.version(), material("m-r", Side.RESPONDENT, "双方提交的测试连接结构文本", true, null), AUTHOR, NOW);
    }
    private static State ready() {
        var state = withMaterials();
        state = selectClaim(state, state.version(), claim(1), AUTHOR, NOW);
        state = addFinding(state, state.version(), finding("f1", "e1", "m-a", Relation.SUPPORT, "外壳"), AUTHOR, NOW);
        state = addFinding(state, state.version(), finding("f2", "e2", "m-a", Relation.SUPPORT, "弹簧"), AUTHOR, NOW);
        var law = "原创工程法条文本；不是真实法律依据";
        return addCitation(state, state.version(), new Citation("law-a", "case-a", source("law-a", law), law,
                "人工解释（测试）", "工程验证引用版本", "测试地域", "辅助工作", DATE), AUTHOR, NOW);
    }
    private static State reported(State state) {
        return generateReport(state, state.version(), Policy.standard(), AUTHOR, NOW);
    }
    private static void rejects(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(IllegalArgumentException.class, action).getMessage());
    }

    @Test void singleCaseInputToIndependentReviewWorksWithoutOfficialDecision() {
        var state = reported(ready()); var draft = state.report(1);
        assertTrue(draft.complete()); assertEquals(6, draft.sections().size());
        assertEquals(2, draft.diagnostics().size());
        assertTrue(draft.sections().get(3).content().contains("弹簧"));
        var submitted = submit(state, state.version(), 1, draft.contentSha256(), AUTHOR, NOW);
        var reviewed = review(submitted, submitted.version(), 1, draft.contentSha256(), ReviewState.REVIEWED_NOT_OFFICIAL,
                "已复核工程输入映射；不是裁决", REVIEWER, NOW);
        assertEquals(ReviewState.REVIEWED_NOT_OFFICIAL, reviewed.report(1).reviewState());
        assertEquals(draft.contentSha256(), reviewed.report(1).reviews().getFirst().reportSha256());
        assertEquals(REVIEWER, reviewed.audits().getLast().actor());
        assertEquals(ReviewState.DRAFT, state.report(1).reviewState());
    }
    @Test void incompleteInputStillProducesDraftButCannotSubmit() {
        var state = reported(opened()); var report = state.report(1);
        assertFalse(report.complete());
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.code().equals("PARTY_MATERIAL_MISSING")));
        rejects("REPORT_INCOMPLETE", () -> submit(state, state.version(), 1, report.contentSha256(), AUTHOR, NOW));
    }
    @Test void unreadableMaterialIsAnExplicitGapAndNeverExposesBody() {
        var state = opened();
        state = addMaterial(state, state.version(), material("hidden", Side.APPLICANT, "DO_NOT_DISPLAY_HIDDEN", false, null), AUTHOR, NOW);
        var result = reported(state).report(1);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("MATERIAL_UNREADABLE")));
        assertFalse(result.sections().stream().anyMatch(s -> s.content().contains("DO_NOT_DISPLAY_HIDDEN")));
        assertTrue(result.sections().get(1).sources().isEmpty());
    }
    @Test void materialCorrectionPreservesOriginalAndRequiresNewCorrespondence() {
        var old = reported(ready()); var beforeHash = old.report(1).contentSha256();
        var corrected = addMaterial(old, old.version(), material("m-a-2", Side.APPLICANT, "更正：外壳没有弹簧", true, "m-a"), AUTHOR, NOW);
        assertEquals(3, corrected.materials().size()); assertEquals(2, corrected.activeMaterials().size());
        assertTrue(corrected.stale(corrected.report(1))); assertEquals(beforeHash, corrected.report(1).contentSha256());
        var newReport = reported(corrected).report(2);
        assertEquals(2, newReport.diagnostics().stream().filter(d -> d.code().equals("ELEMENT_SUPPORT_MISSING")).count());
        rejects("REPORT_INPUT_OR_HEAD_STALE", () -> submit(corrected, corrected.version(), 1, beforeHash, AUTHOR, NOW));
    }
    @Test void materialReplacementCannotMoveAnotherPartySubmission() {
        var state = withMaterials();
        rejects("MATERIAL_SUBMISSION_LINEAGE_CONFLICT", () -> addMaterial(state, state.version(),
                material("wrong-side", Side.RESPONDENT, "文本", true, "m-a"), AUTHOR, NOW));
    }
    @Test void alreadySupersededMaterialCannotBeSupersededAgain() {
        var state = withMaterials();
        var corrected = addMaterial(state, state.version(), material("new", Side.APPLICANT, "新文本", true, "m-a"), AUTHOR, NOW);
        rejects("SUPERSEDED_MATERIAL_NOT_CURRENT", () -> addMaterial(corrected, corrected.version(),
                material("new2", Side.APPLICANT, "新文本2", true, "m-a"), AUTHOR, NOW));
    }
    @Test void sameBytesFromDifferentSidesRemainSeparateSubmissions() {
        var state = opened();
        state = addMaterial(state, state.version(), material("left", Side.APPLICANT, "同一字节", true, null), AUTHOR, NOW);
        state = addMaterial(state, state.version(), material("right", Side.RESPONDENT, "同一字节", true, null), AUTHOR, NOW);
        assertEquals(2, state.materials().size());
        assertEquals(state.materials().getFirst().source().sha256(), state.materials().getLast().source().sha256());
        assertNotEquals(state.materials().getFirst().side(), state.materials().getLast().side());
    }
    @Test void crossCaseMaterialIsRejectedWithoutMutation() {
        var state = opened(); var content = "跨案材料";
        var other = new Material("other", "case-b", Side.APPLICANT, "p", content, "说明", source("other", content), true, null);
        rejects("CROSS_CASE_INPUT", () -> addMaterial(state, 1, other, AUTHOR, NOW));
        assertTrue(state.materials().isEmpty()); assertEquals(1, state.audits().size());
    }
    @Test void sourceBodyMismatchIsRejected() {
        rejects("SOURCE_CONTENT_HASH_MISMATCH", () -> new Material("x", "case-a", Side.APPLICANT, "p", "实际内容",
                "说明", source("x", "其他内容"), true, null));
    }
    @Test void featureNotInClaimIsRejected() {
        rejects("ELEMENT_NOT_IN_ORIGINAL", () -> new Claim("case-a", "test", "1", 1, CLAIM_TEXT, source("c", CLAIM_TEXT),
                List.of(new Element("missing", "未公开的测试特征")), "产品", "原因"));
    }
    @Test void duplicateNecessaryElementIdIsRejected() {
        rejects("DUPLICATE_ID", () -> new Claim("case-a", "test", "1", 1, CLAIM_TEXT, source("c", CLAIM_TEXT),
                List.of(new Element("same", "外壳"), new Element("same", "弹簧")), "产品", "原因"));
    }
    @Test void missingNecessaryElementRemainsBlocking() {
        var state = selectClaim(withMaterials(), 3, claim(1), AUTHOR, NOW);
        state = addFinding(state, state.version(), finding("one", "e1", "m-a", Relation.SUPPORT, "外壳"), AUTHOR, NOW);
        assertTrue(reported(state).report(1).diagnostics().stream().anyMatch(d -> d.code().equals("ELEMENT_SUPPORT_MISSING") && d.objectId().equals("e2")));
    }
    @Test void opposingEvidenceIsNotErasedBySupport() {
        var state = ready();
        state = addFinding(state, state.version(), finding("oppose", "e1", "m-r", Relation.OPPOSING, "结构"), REVIEWER, NOW);
        var result = reported(state).report(1);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ELEMENT_CONFLICT")));
        assertTrue(result.sections().get(3).content().contains("OPPOSING")); assertFalse(result.complete());
    }
    @Test void uncertainCorrespondenceIsNotConvertedToSupport() {
        var state = selectClaim(withMaterials(), 3, claim(1), AUTHOR, NOW);
        state = addFinding(state, state.version(), finding("uncertain", "e1", "m-a", Relation.UNCERTAIN, "外壳"), AUTHOR, NOW);
        var result = reported(state).report(1);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ELEMENT_UNCERTAIN")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("ELEMENT_SUPPORT_MISSING") && d.objectId().equals("e1")));
    }
    @Test void wrongExcerptIsRejected() {
        var state = ready();
        rejects("EXCERPT_NOT_IN_MATERIAL", () -> addFinding(state, state.version(), finding("wrong", "e1", "m-a", Relation.SUPPORT, "虚构摘录"), AUTHOR, NOW));
    }
    @Test void wrongClaimRevisionIsRejected() {
        var state = ready();
        var wrong = new Finding("wrong", "case-a", 2, "e1", "m-a", Relation.SUPPORT, "外壳", "原因");
        rejects("CLAIM_REVISION_CONFLICT", () -> addFinding(state, state.version(), wrong, AUTHOR, NOW));
    }
    @Test void changingClaimPreservesHistoryAndInvalidatesOldFindings() {
        var old = reported(ready());
        var changed = selectClaim(old, old.version(), claim(2), AUTHOR, NOW);
        assertEquals(2, changed.claims().size()); assertEquals(1, changed.claims().getFirst().revision());
        assertTrue(changed.stale(changed.report(1)));
        assertEquals(2, reported(changed).report(2).diagnostics().stream().filter(d -> d.code().equals("ELEMENT_SUPPORT_MISSING")).count());
    }
    @Test void legalContextMismatchCannotBeAdopted() {
        var state = ready(); var content = "测试原文";
        var wrong = new Citation("wrong", "case-a", source("l", content), content, "解释", "理由", "其他地域", "辅助工作", DATE);
        rejects("CITATION_CONTEXT_MISMATCH", () -> addCitation(state, state.version(), wrong, AUTHOR, NOW));
    }
    @Test void legalOriginalAndHumanExplanationStaySeparate() {
        var report = reported(ready()).report(1);
        var law = report.sections().stream().filter(s -> s.id().equals("law")).findFirst().orElseThrow();
        assertTrue(law.content().contains("原文：原创工程法条文本")); assertTrue(law.content().contains("人工解释：人工解释（测试）"));
        assertEquals("v1", law.sources().getFirst().version());
    }
    @Test void optimisticVersionMismatchHasNoSideEffects() {
        var state = opened();
        rejects("WORK_VERSION_CONFLICT", () -> addMaterial(state, 0, material("m", Side.APPLICANT, "字节", true, null), AUTHOR, NOW));
        assertEquals(1, state.version()); assertTrue(state.materials().isEmpty());
    }
    @Test void authorCannotReviewOwnReport() {
        var state = reported(ready()); var hash = state.report(1).contentSha256();
        var submitted = submit(state, state.version(), 1, hash, AUTHOR, NOW);
        rejects("INDEPENDENT_REVIEW_REQUIRED", () -> review(submitted, submitted.version(), 1, hash, ReviewState.REVIEWED_NOT_OFFICIAL, "原因", AUTHOR, NOW));
    }
    @Test void inputContributorCannotReviewEvenWhenAnotherPersonWroteReport() {
        var state = generateReport(ready(), ready().version(), Policy.standard(), REVIEWER, NOW); var hash = state.report(1).contentSha256();
        var submitted = submit(state, state.version(), 1, hash, REVIEWER, NOW);
        rejects("INDEPENDENT_REVIEW_REQUIRED", () -> review(submitted, submitted.version(), 1, hash, ReviewState.REVIEWED_NOT_OFFICIAL, "原因", AUTHOR, NOW));
    }
    @Test void submitterIsAlsoExcludedFromReview() {
        var state = reported(ready()); var hash = state.report(1).contentSha256();
        var submitted = submit(state, state.version(), 1, hash, "trusted-submitter-c", NOW);
        rejects("INDEPENDENT_REVIEW_REQUIRED", () -> review(submitted, submitted.version(), 1, hash, ReviewState.RETURNED, "退回", "trusted-submitter-c", NOW));
    }
    @Test void unsubmittedReportCannotBeReviewed() {
        var state = reported(ready());
        rejects("REPORT_NOT_SUBMITTED", () -> review(state, state.version(), 1, state.report(1).contentSha256(), ReviewState.REVIEWED_NOT_OFFICIAL, "原因", REVIEWER, NOW));
    }
    @Test void wrongHashIsRejected() {
        var state = reported(ready());
        rejects("REPORT_HASH_CONFLICT", () -> submit(state, state.version(), 1, sha256("not-report"), AUTHOR, NOW));
    }
    @Test void returnAndHumanRevisionPreserveExactOldVersion() {
        var state = reported(ready()); var hash = state.report(1).contentSha256();
        state = submit(state, state.version(), 1, hash, AUTHOR, NOW);
        state = review(state, state.version(), 1, hash, ReviewState.RETURNED, "请澄清人工解释", REVIEWER, NOW);
        var returned = state;
        rejects("REPORT_NOT_DRAFT", () -> submit(returned, returned.version(), 1, hash, AUTHOR, NOW));
        var revised = reviseReport(state, state.version(), 1, hash, "analysis", "已补充逐项工程解释", "根据退回澄清", Policy.standard(), AUTHOR, NOW);
        assertEquals(2, revised.reports().size()); assertEquals(ReviewState.RETURNED, revised.report(1).reviewState());
        assertEquals(hash, revised.report(1).contentSha256()); assertNotEquals(hash, revised.report(2).contentSha256());
        assertEquals(ReviewState.DRAFT, revised.report(2).reviewState()); assertTrue(revised.report(2).reviews().isEmpty());
    }
    @Test void auxiliaryBoundaryCannotBeEditedAway() {
        var state = reported(ready());
        rejects("AUXILIARY_BOUNDARY_NOT_EDITABLE", () -> reviseReport(state, state.version(), 1, state.report(1).contentSha256(), "limitations",
                "正式裁决", "测试越权", Policy.standard(), AUTHOR, NOW));
    }
    @Test void reviewCannotBeRepeatedOrMutated() {
        var state = reported(ready()); var hash = state.report(1).contentSha256();
        state = submit(state, state.version(), 1, hash, AUTHOR, NOW);
        var reviewed = review(state, state.version(), 1, hash, ReviewState.REVIEWED_NOT_OFFICIAL, "辅助复核", REVIEWER, NOW);
        rejects("REPORT_NOT_SUBMITTED", () -> review(reviewed, reviewed.version(), 1, hash, ReviewState.RETURNED, "覆盖历史", "trusted-other", NOW));
    }
    @Test void oldReportCannotBeSubmittedAfterNewGeneration() {
        var first = reported(ready()); var next = reported(first);
        rejects("REPORT_INPUT_OR_HEAD_STALE", () -> submit(next, next.version(), 1, first.report(1).contentSha256(), AUTHOR, NOW));
    }
    @Test void policyCannotRemoveMandatoryBoundaries() {
        rejects("INVALID_REPORT_POLICY", () -> new Policy("unsafe", List.of("case"), 100));
        var state = ready();
        rejects("REPORT_SIZE_LIMIT", () -> generateReport(state, state.version(), new Policy("tiny", Policy.standard().sectionOrder(), 1), AUTHOR, NOW));
    }
    @Test void collectionsAreDefensivelyCopiedAndUnmodifiable() {
        var elements = new ArrayList<>(List.of(new Element("e1", "外壳")));
        var claim = new Claim("case-a", "test", "1", 1, CLAIM_TEXT, source("claim", CLAIM_TEXT), elements, "产品", "理由");
        elements.clear(); assertEquals(1, claim.elements().size());
        assertThrows(UnsupportedOperationException.class, () -> claim.elements().clear());
        assertThrows(UnsupportedOperationException.class, () -> ready().audits().clear());
    }
    @Test void reportDigestBindsActualTextAndPreciseSourcesAndIsDeterministic() {
        var one = reported(ready()).report(1); var two = reported(ready()).report(1);
        assertEquals(one.contentSha256(), two.contentSha256());
        var altered = new ArrayList<>(one.sections()); altered.set(0, new Section("case", "篡改", one.sections().getFirst().sources()));
        rejects("REPORT_CONTENT_HASH_MISMATCH", () -> new Report(1, one.inputVersion(), one.policyVersion(), AUTHOR, one.contributors(), altered,
                one.diagnostics(), one.contentSha256(), ReviewState.DRAFT, List.of()));
    }

    @Test void sameSourceVersionCannotHideChangedContent() {
        var state = withMaterials(); var changed = "更改后的文本";
        var originalSource = state.materials().getFirst().source();
        var source = new Source(originalSource.id(), originalSource.version(), originalSource.locator(), sha256(changed), InputKind.PROJECT_AUTHORED);
        var newMaterial = new Material("changed", "case-a", Side.APPLICANT, "APPLICANT-submitter", changed, "补正", source, true, "m-a");
        rejects("SOURCE_VERSION_CONTENT_CONFLICT", () -> addMaterial(state, state.version(), newMaterial, AUTHOR, NOW));
    }
    @Test void sameSourceVersionCannotRelabelProjectInputAsPublic() {
        var state = withMaterials(); var original = state.materials().getFirst(); var prior = original.source();
        var source = new Source(prior.id(), prior.version(), prior.locator(), prior.sha256(), InputKind.PUBLIC);
        var relabeled = new Material("relabeled", "case-a", Side.APPLICANT, "APPLICANT-submitter", original.content(), "说明", source, true, null);
        rejects("SOURCE_VERSION_CONTENT_CONFLICT", () -> addMaterial(state, state.version(), relabeled, AUTHOR, NOW));
    }
    @Test void unavailableMaterialCannotBeUsedForCorrespondence() {
        var state = selectClaim(withMaterials(), 3, claim(1), AUTHOR, NOW);
        state = addMaterial(state, state.version(), material("unreadable", Side.RESPONDENT, "外壳", false, null), AUTHOR, NOW);
        var current = state;
        rejects("MATERIAL_NOT_READABLE", () -> addFinding(current, current.version(), finding("f", "e1", "unreadable", Relation.SUPPORT, "外壳"), AUTHOR, NOW));
    }
    @Test void oldInputCannotBeApprovedEvenWhenAlreadySubmitted() {
        var state = reported(ready()); var hash = state.report(1).contentSha256();
        state = submit(state, state.version(), 1, hash, AUTHOR, NOW);
        var changed = addMaterial(state, state.version(), material("late", Side.RESPONDENT, "补充争议", true, null), AUTHOR, NOW);
        rejects("REPORT_INPUT_OR_HEAD_STALE", () -> review(changed, changed.version(), 1, hash, ReviewState.REVIEWED_NOT_OFFICIAL, "批准", REVIEWER, NOW));
    }
    @Test void wrongCaseCitationAndFindingAreRejected() {
        var state = ready(); var text = "原创测试法律文本";
        var citation = new Citation("other", "case-b", source("other", text), text, "解释", "原因", "测试地域", "辅助工作", DATE);
        rejects("CROSS_CASE_INPUT", () -> addCitation(state, state.version(), citation, AUTHOR, NOW));
        var finding = new Finding("other", "case-b", 1, "e1", "m-a", Relation.SUPPORT, "外壳", "原因");
        rejects("CROSS_CASE_INPUT", () -> addFinding(state, state.version(), finding, AUTHOR, NOW));
    }
    @Test void requiredLegalContextIsNotGuessed() {
        var original = opened().caseInput();
        var input = new CaseInput(original.id(), original.organization(), original.dataScope(), original.name(), original.applicant(),
                original.respondent(), original.issues(), null, null, null, original.source());
        var state = reported(open(input, AUTHOR, NOW));
        assertTrue(state.report(1).diagnostics().stream().anyMatch(d -> d.code().equals("LEGAL_CONTEXT_MISSING")));
    }
    @Test void revisionCannotClearMissingDiagnosticsOrMutateCitations() {
        var state = reported(opened()); var original = state.report(1);
        var revised = reviseReport(state, state.version(), 1, original.contentSha256(), "analysis", "人工补充说明", "说明缺口", Policy.standard(), AUTHOR, NOW);
        assertEquals(original.diagnostics(), revised.report(2).diagnostics()); assertFalse(revised.report(2).complete());
        assertEquals(original.sections().get(4).sources(), revised.report(2).sections().get(4).sources());
    }
    @Test void sectionOrderingIsConfigurableWithoutChangingAuxiliaryMeaning() {
        var state = ready(); var reversed = new ArrayList<>(Policy.standard().sectionOrder()); Collections.reverse(reversed);
        var generated = generateReport(state, state.version(), new Policy("ADJ_ORDER/v2", reversed, 100_000), AUTHOR, NOW).report(1);
        assertEquals("limitations", generated.sections().getFirst().id()); assertTrue(generated.complete());
        assertEquals("ADJ_ORDER/v2", generated.policyVersion());
        assertTrue(generated.sections().getFirst().content().contains("裁决签发由有权机关"));
    }
    @Test void oppositeCorrespondenceCanFormReviewableAnalysisWithoutPredeterminingOutcome(){
        var state=selectClaim(withMaterials(),3,claim(1),AUTHOR,NOW);
        state=addFinding(state,state.version(),finding("negative-e1","e1","m-r",Relation.OPPOSING,"结构"),AUTHOR,NOW);
        state=addFinding(state,state.version(),finding("positive-e2","e2","m-a",Relation.SUPPORT,"弹簧"),AUTHOR,NOW);
        var citation=ready().citations().getFirst();state=addCitation(state,state.version(),citation,AUTHOR,NOW);
        var report=reported(state).report(1);assertTrue(report.complete());assertTrue(report.diagnostics().stream().anyMatch(d->d.code().equals("ELEMENT_OPPOSING")&&!d.blocking()));
        var submitted=submit(reported(state),state.version()+1,1,report.contentSha256(),AUTHOR,NOW);assertEquals(ReviewState.SUBMITTED,submitted.report(1).reviewState());
        var strict=generateReport(state,state.version(),new Policy("strict-v2",Policy.standard().sectionOrder(),100000,true),AUTHOR,NOW);assertFalse(strict.report(1).complete());
    }

}
