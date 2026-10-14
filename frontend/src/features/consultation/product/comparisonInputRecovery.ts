export type ComparisonPacket={stage:'PREPARE'|'CONFIRM'|'CREATE';path:string;key:string;bodyJson:string};
/** Session-local pending intent, never a permission or another task/status ledger. */
export class ComparisonInputRecovery {
 pending:ComparisonPacket|null=null;
 begin(stage:ComparisonPacket['stage'],path:string,body:unknown){if(this.pending)throw new Error('请先对账原对照请求。');return this.pending=Object.freeze({stage,path,key:crypto.randomUUID(),bodyJson:JSON.stringify(body)});}
 confirm(packet:ComparisonPacket){if(this.pending!==packet)return false;this.pending=null;return true;}
 reject(packet:ComparisonPacket,error:unknown){if(this.pending!==packet)return;const status=error&&typeof error==='object'&&'status' in error?Number(error.status):0;if(status>=400&&status<500&&![408,429].includes(status))this.pending=null;}
 clear(){this.pending=null;}
}
