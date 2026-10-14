/** Display only: never changes persisted states, request identity or permissions. */
export function candidateLabel(index:number,document?:{documentId:string;documentVersion:string}) {
 return document ? `${document.documentId} · ${document.documentVersion}` : `候选 ${index+1}`;
}
export function candidateStatus(status:string):string {
 return ({QUEUED:'待计算',RUNNING:'计算中，尚未提交结果',COMMITTED:'结果已提交',FAILED:'计算失败',CANCELLED:'已取消'} as Record<string,string>)[status] ?? '状态尚未核实，请重新读取';
}
export function startStatus(status:string|undefined):string {
 return ({ACCEPTED:'请求已接收，尚未确认计算开始',DISPATCHING:'正在交接计算请求',RECONCILE_REQUIRED:'处理结果尚未确认，请查询原启动回执',ADMISSION_REQUIRED:'服务已接收，计算条件尚未满足',COMMITTED:'结果已提交，可查看对照矩阵',FAILED:'此次计算请求失败，请查看原因',CANCELLED:'任务已取消，不会重新发起计算'} as Record<string,string>)[status??''] ?? '处理结果尚未确认，请查询原启动回执';
}
export function failureReason(code:string|null):string {
 return ({CANDIDATE_FAILED_BEFORE_MODEL_DISPATCH:'此候选已确认在模型调用前失败，未提交矩阵。',EXECUTION_WINDOW_INSUFFICIENT:'剩余执行窗口不足，模型请求未发出，未提交矩阵。',MATRIX_RESPONSE_INVALID:'已收到模型响应但未通过矩阵校验，未提交结果；实际调用记录仍保留。'} as Record<string,string>)[code??''] ?? '此候选失败；请按原回执核对，不能据此推断未调用模型。';
}
