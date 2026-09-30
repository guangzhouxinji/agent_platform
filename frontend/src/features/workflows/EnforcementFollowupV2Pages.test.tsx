import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import EnforcementDocumentTemplateV2Page from "./EnforcementDocumentTemplateV2Page";
import EnforcementLegalReferenceV2Page from "./EnforcementLegalReferenceV2Page";

const api = vi.hoisted(() => ({ complete: vi.fn(), identity: vi.fn(), request: vi.fn() }));
vi.mock("./enforcementNextV2Api", async importOriginal => {
  const actual = await importOriginal<typeof import("./enforcementNextV2Api")>();
  return { ...actual, completeEnforcementLogin: api.complete, readEnforcementIdentity: api.identity,
    enforcementRequest: api.request };
});

beforeEach(() => {
  api.complete.mockReset().mockResolvedValue("product-token");
  api.identity.mockReset().mockResolvedValue({ issuer: "https://issuer", subject: "controlled-user", sessionId: "session-1",
    grants: [
      { organization: "ORG-ENF", domain: "ENFORCEMENT", resourceType: "DOCUMENT_TEMPLATE", resourceId: "*", action: "CREATE", purpose: "DOCUMENT_TEMPLATE" },
      { organization: "ORG-ENF", domain: "ENFORCEMENT", resourceType: "LEGAL_REFERENCE", resourceId: "*", action: "SEARCH", purpose: "LEGAL_REFERENCE" }
    ] });
  api.request.mockReset().mockResolvedValue([]);
});
afterEach(cleanup);

describe("ENF-12 and ENF-17 product workfaces", () => {
  it("derives the document-template organization from the current grant", async () => {
    render(<EnforcementDocumentTemplateV2Page />);
    expect(await screen.findByText("执法文书模板")).toBeInTheDocument();
    expect(await screen.findByDisplayValue("ORG-ENF")).toBeDisabled();
    expect(screen.getByText("上传、预览与版本管理；不产生正式文号、签章或签发状态")).toBeInTheDocument();
  });

  it("keeps legal candidates separate from a confirmed legal conclusion", async () => {
    render(<EnforcementLegalReferenceV2Page />);
    expect(await screen.findByText("法律依据追溯")).toBeInTheDocument();
    expect(await screen.findByDisplayValue("ORG-ENF")).toBeDisabled();
    expect(screen.getByText("逐字原文与解释分离；候选始终需要人工复核")).toBeInTheDocument();
  });
});
