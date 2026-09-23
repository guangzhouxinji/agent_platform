export async function installDevelopmentSourceRoutes(page) {
  const clue = { source: { sourceSystem: "v0-synthetic-enforcement", sourceId: "V0-SYN-CLUE-001", version: "v1", location: "body" }, subjects: [{ roleAsStated: "经营者", name: "宁江瑞衡新材料有限公司" }] };
  const evidence = [{ source: { sourceSystem: "v0-synthetic-enforcement", sourceId: "V0-SYN-EVD-001", version: "v1", location: "field:content" }, relatedClueId: "V0-SYN-CLUE-001", availability: "AVAILABLE", issue: "经营主体与商品页面" }];
  const caseItem = { kind: "case", id: "V0-SYN-ADJ-CASE-001", version: "v1", location: "case" };
  const material = { source: { kind: "material", id: "V0-SYN-ADJ-MAT-001", version: "v1", location: "material" }, caseRef: caseItem, data: { available: true, partyStatements: ["申请人陈述"], unresolvedFacts: [], missingItems: [], conflicts: [] } };
  const response = (items) => JSON.stringify({ dataMode: "SYNTHETIC_V0", catalogVersion: "fixture-v1", limitation: "内部开发夹具；不得作为正式业务事实。", items });
  await page.route("**/api/v0/development-sources/enforcement/clues", route => route.fulfill({ status: 200, contentType: "application/json", body: response([clue]) }));
  await page.route("**/api/v0/development-sources/enforcement/evidence", route => route.fulfill({ status: 200, contentType: "application/json", body: response(evidence) }));
  await page.route("**/api/v0/development-sources/adjudication/cases", route => route.fulfill({ status: 200, contentType: "application/json", body: response([caseItem]) }));
  await page.route("**/api/v0/development-sources/adjudication/materials", route => route.fulfill({ status: 200, contentType: "application/json", body: response([material]) }));
}
