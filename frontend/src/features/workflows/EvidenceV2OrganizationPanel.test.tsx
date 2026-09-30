import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import EvidenceV2OrganizationPanel from "./EvidenceV2OrganizationPanel";
import type { EvidenceRevision } from "./evidenceV2Api";

const revision: EvidenceRevision = {
  id: "ORG-CASE-001", version: 4, caseRef: "PROJECT-CASE-001", caseVersion: "1",
  ruleId: "RULE-001", ruleVersion: 2, state: "REVIEW_REQUIRED",
  evidenceSet: [{ id: "MAT-A", version: "3", sha256: "a".repeat(64) }],
  matters: [{ id: "ITEM_IDENTITY", coverage: "PRESENT" }], links: [], times: [],
  unrelated: [], unprocessed: [], exclusions: []
};

describe("Evidence V2 exact relation location", () => {
  it("reads stable server pages and previews the verified original at its locator", async () => {
    const requests: string[] = [];
    const request = async <T,>(path: string): Promise<T> => {
      requests.push(path);
      if (path.endsWith("/versions/4")) return revision as T;
      if (path.includes("/preview?")) return {
        evidenceId: "MAT-A", version: "3", sha256: "a".repeat(64), locator: "line 2",
        text: "first line\noriginal value\n", locatedValue: "original value"
      } as T;
      if (path.includes("cursor=")) return { items: [{ matterId: "ITEM_IDENTITY", evidenceId: "MAT-B",
        evidenceVersion: 3, locator: "line 3", value: "other", relation: "CONTRADICT_CANDIDATE",
        originKind: "MACHINE_CANDIDATE", reason: "", sha256: "b".repeat(64) }],
        total: 2, nextCursor: null } as T;
      return { items: [{ matterId: "ITEM_IDENTITY", evidenceId: "MAT-A",
        evidenceVersion: 3, locator: "line 2", value: "original value", relation: "SUPPORT_CANDIDATE",
        originKind: "MACHINE_CANDIDATE", reason: "", sha256: "a".repeat(64) }],
        total: 2, nextCursor: "cursor-next" } as T;
    };
    render(<EvidenceV2OrganizationPanel request={request} run={async action => action()} busy={false} />);
    fireEvent.change(screen.getByLabelText("整理 ID"), { target: { value: "ORG-CASE-001" } });
    fireEvent.change(screen.getByLabelText("版本"), { target: { value: "4" } });
    fireEvent.click(screen.getByRole("button", { name: "读取" }));
    await screen.findByText("ORG-CASE-001 · v4");
    expect(screen.getByText(/line 2 · original value/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "ITEM_IDENTITY 下一页" }));
    await screen.findByText("MAT-B");
    expect(screen.queryByText(/line 2 · original value/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "ITEM_IDENTITY 上一页" }));
    await screen.findByText(/line 2 · original value/);
    fireEvent.click(screen.getByRole("button", { name: "预览" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/original value/)).toHaveClass("enf4-located");
    expect(requests).toContain("/evidence-organizations/ORG-CASE-001/versions/4/links?matterId=ITEM_IDENTITY&pageSize=5&cursor=cursor-next");
    expect(requests).toContain("/evidence-organizations/ORG-CASE-001/versions/4/materials/MAT-A/preview?locator=line%202");
    await waitFor(() => expect(requests).toHaveLength(5));
  });
});
