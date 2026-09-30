import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import type { UploadFile } from "antd";
import { afterEach, describe, expect, it, vi } from "vitest";
import { flowTabs, type Clue, type Material, type MaterialAction, type Revision } from "./EnforcementMaterialFlowTabs";

afterEach(() => vi.unstubAllGlobals());

describe("enforcement material preview", () => {
  it("clears the previously visible material after current authorization is revoked", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ status: 403, ok: false }));
    function View() {
      const [token, setToken] = useState<string | null>("product-token");
      const [material, setMaterial] = useState<Material | null>({
        id: "material-1", clueId: "clue-1", sha256: "digest", state: "PENDING_VERIFICATION", collector: "operator"
      });
      const [clue, setClue] = useState<Clue | null>(null);
      const [revisions, setRevisions] = useState<Revision[]>([]);
      const [materialActions, setMaterialActions] = useState<MaterialAction[]>([]);
      const [file, setFile] = useState<UploadFile[]>([]);
      const tab = flowTabs({ token, busy: false, onUnauthorized: () => { setToken(null); setMaterial(null); },
        request: async <T,>() => ({} as T), run: async action => { try { await action(); } catch { /* shown by page */ } },
        report: () => {}, clue, setClue, revisions, setRevisions, material, setMaterial,
        materialActions, setMaterialActions, file, setFile
      }).find(item => item.key === "material");
      return <>{tab?.children}</>;
    }
    render(<View />);
    expect(screen.getByText("material-1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "预览原件" }));
    await waitFor(() => expect(screen.queryByText("material-1")).not.toBeInTheDocument());
    expect(fetch).toHaveBeenCalledWith("/v2/enforcement/materials/material-1/preview", {
      headers: { Authorization: "Bearer product-token" }
    });
  });
});
