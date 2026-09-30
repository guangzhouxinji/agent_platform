import { lazy, Suspense } from "react";
import { Spin } from "antd";
import { Navigate, Route, Routes } from "react-router-dom";
import { AppShell } from "../layouts/AppShell";
import { routeItems } from "./navigation";

const AgentWorkspacePage = lazy(() => import("../features/workspace/AgentWorkspacePage"));
const PatentLibraryPage = lazy(() => import("../features/libraries/LibraryWorkspace").then((module) => ({ default: module.PatentLibraryPage })));
const TrademarkLibraryPage = lazy(() => import("../features/libraries/LibraryWorkspace").then((module) => ({ default: module.TrademarkLibraryPage })));
const CaseLibraryPage = lazy(() => import("../features/libraries/LibraryWorkspace").then((module) => ({ default: module.CaseLibraryPage })));
const ConsultationPage = lazy(() => import("../features/consultation/ConsultationPage"));
const ConsultationFlowPage = lazy(() => import("../features/consultation/ConsultationFlowPage"));
const IntentTestPage = lazy(() => import("../features/consultation/IntentTestPage"));
const AgentConfigurationPage = lazy(() => import("../features/consultation/AgentConfigurationPage"));
const PatentSearchPage = lazy(() => import("../features/search/SearchWorkspaces").then((module) => ({ default: module.PatentSearchPage })));
const TrademarkSearchPage = lazy(() => import("../features/search/SearchWorkspaces").then((module) => ({ default: module.TrademarkSearchPage })));
const CaseSearchPage = lazy(() => import("../features/search/CaseSearchWorkspace"));
const PatentComparisonPage = lazy(() => import("../features/comparison/ComparisonWorkspaces").then((module) => ({ default: module.PatentComparisonPage })));
const TrademarkComparisonPage = lazy(() => import("../features/comparison/ComparisonWorkspaces").then((module) => ({ default: module.TrademarkComparisonPage })));
const EnforcementPage = lazy(() => import("../features/workflows/BusinessWorkspaces").then((module) => ({ default: module.EnforcementPage })));
const EnforcementDiscoveryRulesPage = lazy(() => import("../features/workflows/EnforcementDiscoveryRulesPage"));
const EnforcementClueDiscoveryPage = lazy(() => import("../features/workflows/EnforcementClueDiscoveryPage"));
const EnforcementClueParsingPage = lazy(() => import("../features/workflows/EnforcementClueParsingPage"));
const EnforcementEvidencePage = lazy(() => import("../features/workflows/EnforcementEvidenceV2Page"));
const EnforcementHandoffPage = lazy(() => import("../features/workflows/EnforcementHandoffV2Page"));
const EnforcementProcedurePage = lazy(() => import("../features/workflows/EnforcementProcedureV2Page"));
const EnforcementConductPage = lazy(() => import("../features/workflows/EnforcementConductV2Page"));
const EnforcementJobPackPage = lazy(() => import("../features/workflows/EnforcementJobPackV2Page"));
const EnforcementMaterialV2Page = lazy(() => import("../features/workflows/EnforcementMaterialV2Page"));
const EnforcementDocumentTemplatePage = lazy(() => import("../features/workflows/EnforcementDocumentTemplateV2Page"));
const EnforcementLegalReferencePage = lazy(() => import("../features/workflows/EnforcementLegalReferenceV2Page"));
const AdjudicationPage = lazy(() => import("../features/workflows/BusinessWorkspaces").then((module) => ({ default: module.AdjudicationPage })));

const loading = <div className="route-loading"><Spin size="large" /></div>;

const migratedRoutes: Record<string, React.ReactNode> = {
  "/consultation/flow": <Suspense fallback={loading}><ConsultationFlowPage /></Suspense>,
  "/consultation": <Suspense fallback={loading}><ConsultationPage /></Suspense>,
  "/consultation/intents": <Suspense fallback={loading}><IntentTestPage /></Suspense>,
  "/consultation/agent-configuration": <Suspense fallback={loading}><AgentConfigurationPage /></Suspense>,
  "/search/patents": <Suspense fallback={loading}><PatentSearchPage /></Suspense>,
  "/search/trademarks": <Suspense fallback={loading}><TrademarkSearchPage /></Suspense>,
  "/search/cases": <Suspense fallback={loading}><CaseSearchPage /></Suspense>,
  "/compare/patents": <Suspense fallback={loading}><PatentComparisonPage /></Suspense>,
  "/compare/trademarks": <Suspense fallback={loading}><TrademarkComparisonPage /></Suspense>,
  "/libraries/patents": <Suspense fallback={loading}><PatentLibraryPage /></Suspense>,
  "/libraries/trademarks": <Suspense fallback={loading}><TrademarkLibraryPage /></Suspense>,
  "/libraries/cases": <Suspense fallback={loading}><CaseLibraryPage /></Suspense>,
  "/enforcement": <Suspense fallback={loading}><EnforcementPage /></Suspense>,
  "/enforcement/rules": <Suspense fallback={loading}><EnforcementDiscoveryRulesPage /></Suspense>,
  "/enforcement/discovery": <Suspense fallback={loading}><EnforcementClueDiscoveryPage /></Suspense>,
  "/enforcement/parsing": <Suspense fallback={loading}><EnforcementClueParsingPage /></Suspense>,
  "/enforcement/evidence": <Suspense fallback={loading}><EnforcementEvidencePage /></Suspense>,
  "/enforcement/handoff": <Suspense fallback={loading}><EnforcementHandoffPage /></Suspense>,
  "/enforcement/procedure": <Suspense fallback={loading}><EnforcementProcedurePage /></Suspense>,
  "/enforcement/conduct": <Suspense fallback={loading}><EnforcementConductPage /></Suspense>,
  "/enforcement/job-pack": <Suspense fallback={loading}><EnforcementJobPackPage /></Suspense>,
  "/enforcement/material": <Suspense fallback={loading}><EnforcementMaterialV2Page /></Suspense>,
  "/enforcement/document-templates": <Suspense fallback={loading}><EnforcementDocumentTemplatePage /></Suspense>,
  "/enforcement/legal-references": <Suspense fallback={loading}><EnforcementLegalReferencePage /></Suspense>,
  "/adjudication": <Suspense fallback={loading}><AdjudicationPage /></Suspense>
};

export function AppRoutes() {
  return (
    <Routes>
      <Route index element={<Suspense fallback={loading}><AgentWorkspacePage /></Suspense>} />
      <Route element={<AppShell />}>
        {routeItems.map((item) => (
          <Route
            key={item.path}
            path={item.path.slice(1)}
            element={migratedRoutes[item.path]}
          />
        ))}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
