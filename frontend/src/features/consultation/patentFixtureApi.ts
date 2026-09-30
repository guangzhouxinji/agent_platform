import { apiRequest } from "../../shared/api/client";

export type Feature = { id: string; version: number; text: string };
export type Candidate = { resultVersion: number; candidateId: string; documentVersion: string; title: string; sourceId: string; locator: string; available: boolean };
export type Comparison = { featureKey: string; feature: string; relationship: string; locator: string | null; documentVersion: string | null; current: boolean };
export type FixtureView = {
  taskId: string; taskVersion: number; taskStatus: string; description: string; scope: string;
  features: Feature[]; candidates: Candidate[]; selectedCandidateReference: string | null; comparisons: Comparison[];
  retrievalOutcomes: string[]; humanAvailability: string; limitations: string[];
};
export type Source = { candidateId: string; documentVersion: string; title: string; sourceId: string; locator: string; excerpt: string; limitation: string };

const root = "/api/v0/consultations/patent-fixture";
const key = () => ({ "Idempotency-Key": crypto.randomUUID() });

export const patentFixture = {
  start: (description: string, scope: string, features: string[]) =>
    apiRequest<FixtureView>(root, { method: "POST", headers: key(), body: JSON.stringify({ description, scope, features }) }),
  read: (id: string) => apiRequest<FixtureView>(`${root}/${encodeURIComponent(id)}`),
  act: (view: FixtureView, path: string, extra: Record<string, unknown> = {}) =>
    apiRequest<FixtureView>(`${root}/${encodeURIComponent(view.taskId)}/${path}`, {
      method: "POST", headers: key(), body: JSON.stringify({ expectedTaskVersion: view.taskVersion, ...extra })
    }),
  source: (view: FixtureView, candidate: Candidate) =>
    apiRequest<Source>(`${root}/${encodeURIComponent(view.taskId)}/source-links/${encodeURIComponent(candidate.candidateId)}?version=${encodeURIComponent(candidate.documentVersion)}`)
};
