import { EnforcementAccessDenied } from "./enforcementNextV2Api";

export type DraftWrite = Readonly<{ path: string; bodyJson: string; key: string }>;
/** Memory only. A lost HTTP response is not proof that the server cancelled a command. */
export class EnforcementDraftRecovery {
  private pending: DraftWrite | null = null;
  get request() { return this.pending; }
  begin(path: string, body: unknown, key: string): DraftWrite {
    if (this.pending) throw new Error("请先对账同一文书请求，不能另发写入");
    const bodyJson = JSON.stringify(body);
    if (bodyJson === undefined) throw new Error("文书请求正文不可用");
    return this.pending = Object.freeze({ path, bodyJson, key });
  }
  confirm(request: DraftWrite) {
    if (this.pending !== request) return false;
    this.pending = null;
    return true;
  }
  fail(request: DraftWrite, cause: unknown) {
    const status = cause instanceof Error ? (cause as Error & { status?: number }).status : undefined;
    if (cause instanceof EnforcementAccessDenied || (status !== undefined && status >= 400 && status < 500 && status !== 408)) {
      this.confirm(request);
    }
  }
  clear() { this.pending = null; }
}
