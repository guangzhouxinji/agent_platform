import { completeEnforcementLogin, readEnforcementIdentity } from "./enforcementNextV2Api";

/** Component-local response ownership, independent of document/input read sequencing. */
export class EnforcementDraftSession {
  private epoch = 0;
  private operation = 0;

  capture() {
    const epoch = this.epoch;
    return () => epoch === this.epoch;
  }

  invalidate() {
    this.epoch++;
    this.operation++;
  }

  async login(callbacks: {
    token: (value: string | null) => void;
    organization: (value: string) => void;
    fail: (cause: unknown) => void;
  }) {
    const active = this.capture();
    try {
      const token = await completeEnforcementLogin("document-drafts");
      if (!active()) return;
      callbacks.token(token);
      if (!token) return;
      const identity = await readEnforcementIdentity(token);
      if (!active()) return;
      callbacks.organization(identity.grants.find(g => g.resourceType === "DOCUMENT_DRAFT")?.organization
        ?? identity.grants.find(g => g.resourceType === "DOCUMENT_TEMPLATE")?.organization ?? "");
    } catch (cause) {
      if (active()) callbacks.fail(cause);
    }
  }

  async run(action: () => Promise<void>, callbacks: {
    start: () => void;
    fail: (cause: unknown) => void;
    finish: () => void;
  }) {
    const active = this.capture();
    const operation = ++this.operation;
    callbacks.start();
    try {
      await action();
    } catch (cause) {
      if (active() && operation === this.operation) callbacks.fail(cause);
    } finally {
      if (active() && operation === this.operation) callbacks.finish();
    }
  }
}
