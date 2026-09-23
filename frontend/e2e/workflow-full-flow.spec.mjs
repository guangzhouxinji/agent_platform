import { expect, test } from "@playwright/test";
import { installDevelopmentSourceRoutes } from "./development-source-fixture.mjs";

const envelope = (data, resultType) => ({ resultType, data, limitations: ["SYNTHETIC_V0", "NOT_OFFICIAL_DECISION"] });
const source = (sourceId) => ({ sourceSystem: "v0-synthetic-enforcement", sourceId, version: "v1", location: "body" });

test("enforcement workbench completes create, process, revise, review and exact-version flow", async ({ page }) => {
  await installDevelopmentSourceRoutes(page);
  const open = { workItem: { workItemId: "ENF-WORK-1", organizationRef: "org-v0", clueRef: source("V0-SYN-CLUE-001"), scopeLabel: "SYNTHETIC_V0" }, clueAnalyses: [], evidenceReferences: [], relationSets: [], auxiliaryAnalyses: [], reviews: [], reviewImpacts: [] };
  const processed = { ...open,
    clueAnalyses: [{ analysisId: "CLUE-A-1", version: 1, subjectCandidates: [{ roleAsStated: "经营者", name: "宁江瑞衡新材料有限公司" }], suspectedFacts: [{ value: "展示待核实商品" }], timeCandidates: [{ value: "2026-08-18" }], missingItems: [], conflicts: [] }],
    evidenceReferences: [{ evidenceRefId: "ER-1", version: 1, source: source("V0-SYN-EVD-001"), availability: "AVAILABLE", collector: "开发采集员" }],
    relationSets: [{ relationSetId: "REL-1", version: 1, completeness: "COMPLETE", relations: [{ issue: "商品展示", type: "SUPPORT", reason: "页面记录" }], timeline: [{ kind: "OCCURRENCE", originalValue: "2026-08-18" }], gaps: [] }],
    auxiliaryAnalyses: [{ analysisResultId: "AUX-1", version: 1, auxiliaryOutcome: "SUSPECTED_SUPPORT", items: [{ contentKind: "OBSERVATION", content: "存在待人工核对的支持线索" }], gaps: [], needsReviewReasons: ["需人工复核"] }] };
  let current = processed;
  await page.route("**/api/v0/enforcement/work-items**", async route => {
    const url = new URL(route.request().url());
    if (route.request().method() === "GET" && url.pathname.includes("/versions/")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(processed.clueAnalyses[0], "EXACT_VERSION")) });
    let result = "WORK_ITEM_CREATED";
    if (url.pathname.endsWith("/process")) { current = processed; result = "AUXILIARY_ANALYSIS_READY"; }
    else if (url.pathname.includes("/clue-analyses/")) { current = { ...current, clueAnalyses: [...current.clueAnalyses, { ...current.clueAnalyses.at(-1), version: 2 }] }; result = "CLUE_ANALYSIS_READY"; }
    else if (url.pathname.includes("/auxiliary-analyses/")) { current = { ...current, reviews: [{ action: "CONFIRM", reason: "内部开发流程复核", actorRef: "v0-enforcement-reviewer" }] }; result = "REVIEW_RECORDED"; }
    else current = open;
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(current, result)) });
  });

  await page.goto("enforcement");
  await page.getByRole("button", { name: "创建工作项" }).click();
  await expect(page.getByText("ENF-WORK-1")).toBeVisible();
  await page.getByRole("button", { name: "处理证据并生成分析" }).click();
  await expect(page.getByText("存在待人工核对的支持线索")).toBeVisible();
  await page.getByRole("button", { name: "保存线索修订版" }).click();
  await expect(page.getByRole("button", { name: "线索分析 v2" })).toBeVisible();
  await page.getByRole("button", { name: "确认辅助分析" }).click();
  await expect(page.getByText("CONFIRM", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "线索分析 v1" }).click();
  await expect(page.getByRole("dialog", { name: "精确历史版本" })).toBeVisible();
});

test("adjudication workbench completes inspect, legal citation, draft, revise and test review", async ({ page }) => {
  await installDevelopmentSourceRoutes(page);
  const ref = (kind, id) => ({ kind, id, version: "v1", location: kind });
  const open = { work: { workItemId: "ADJ-WORK-1", organizationRef: "org-v0", caseRef: ref("case", "V0-SYN-ADJ-CASE-001"), materialRefs: [ref("material", "V0-SYN-ADJ-MAT-001")], scopeLabel: "SYNTHETIC_V0" }, inspections: [], candidates: [], citations: [], drafts: [], reviews: [], reviewImpacts: [] };
  let current = open;
  await page.route("**/api/v0/adjudication/work-items**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === "GET" && path.includes("/versions/")) return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(current.drafts[0], "EXACT_VERSION")) });
    let result = "WORK_ITEM_CREATED";
    if (path.endsWith("/material-inspections")) { current = { ...current, inspections: [{ inspectionId: "INS-1", version: 1, partyStatements: ["申请方陈述"], unresolvedFacts: ["待核实产品型号"], missingItems: [], conflicts: [], inputs: current.work.materialRefs }] }; result = "MATERIAL_INSPECTION_READY"; }
    else if (path.endsWith("/legal-candidate-sets")) { current = { ...current, candidates: [{ candidateSetId: "LAW-SET-1", version: 1, entries: [{ code: "LAW-001", location: "article:10", doubts: ["仅开发候选"] }], limitations: ["不构成法律适用结论"] }] }; result = "LEGAL_CANDIDATES_READY"; }
    else if (path.endsWith("/citations/actions")) { current = { ...current, citations: [{ citationId: "CITE-1", version: 1, action: "SELECT", reason: "内部开发流程复核" }] }; result = "CITATION_RECORDED"; }
    else if (path.endsWith("/drafts")) { current = { ...current, drafts: [{ draftId: "DRAFT-1", version: 1, sections: [{ anchor: "facts", content: "开发草稿事实段" }], missingItems: [], conflicts: [] }] }; result = "DRAFT_READY"; }
    else if (path.endsWith("/drafts/actions")) { current = { ...current, drafts: [...current.drafts, { ...current.drafts.at(-1), version: 2, sections: [{ anchor: "facts", content: "人工修订草稿" }] }] }; result = "DRAFT_REVISED"; }
    else if (path.endsWith("/drafts/test-reviews")) { current = { ...current, reviews: [{ action: "CONFIRM", reason: "内部开发流程复核", actorRef: "v0-adjudication-reviewer", notOfficialDecision: true }] }; result = "TEST_REVIEW_RECORDED"; }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(envelope(current, result)) });
  });

  await page.goto("adjudication");
  await page.getByRole("button", { name: "创建裁决工作项" }).click();
  await expect(page.getByText("ADJ-WORK-1")).toBeVisible();
  await page.getByRole("button", { name: "检查案件材料" }).click();
  await expect(page.getByText("申请方陈述")).toBeVisible();
  await page.getByRole("button", { name: "生成法规候选" }).click();
  await expect(page.getByText("LAW-001")).toBeVisible();
  await page.getByRole("button", { name: "选择首个法规候选" }).click();
  await page.getByRole("button", { name: "生成裁决草稿" }).click();
  await expect(page.getByText("开发草稿事实段")).toBeVisible();
  await page.getByRole("button", { name: "保存草稿修订版" }).click();
  await expect(page.getByText("人工修订草稿")).toBeVisible();
  await page.getByRole("button", { name: "确认测试复核" }).click();
  await expect(page.getByText("CONFIRM", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "裁决草稿 v1" }).click();
  await expect(page.getByRole("dialog", { name: "精确历史版本" })).toBeVisible();
});
