import type { DraftWrite } from './enforcementDraftRecovery';

export function assertDraftWriteReceipt(request: DraftWrite, value: unknown) {
      const result=value as Partial<{operation:string;draftId:string;revision:number;contentSha256:string;requestId:string;inputManifestHash:string;ready:boolean;diagnostics:unknown[];exportId:string;templateId:string;state:string;fileSha256:string}>|null;
      const sha=(v:unknown)=>typeof v==="string"&&/^[a-fA-F0-9]{64}$/.test(v);
      if(request.path.endsWith("/prepare")){
        if(!result||!sha(result.inputManifestHash)||typeof result.ready!=="boolean"||!Array.isArray(result.diagnostics))throw new Error("文书预检响应未确认，请对账原请求");
      }else if(request.path.endsWith("/baseline-templates")){
        if(!result||!result.templateId||!Number.isInteger(result.revision)||!result.state||!sha(result.fileSha256))throw new Error("模板发布回执未确认，请对账原请求");
      }else{
        const draftId=decodeURIComponent(request.path.split("/")[2]);
        const operations:Record<string,string>={generate:"GENERATE",candidates:"REGENERATE",revisions:"REVISE",adopt:"ADOPT","submit-review":"SUBMIT_REVIEW",reviews:"REVIEW",exports:"EXPORT"};
        const operation=operations[request.path.split("/").at(-1)!];
        if(!result||result.draftId!==draftId||result.requestId!==request.key||(!operation||result.operation!==operation)||!Number.isInteger(result.revision)||(result.revision??0)<=0||!sha(result.contentSha256)||
          (request.path.endsWith("/exports")&&!result.exportId))throw new Error("文书回执与原请求不一致，请对账原请求");
      }
}
