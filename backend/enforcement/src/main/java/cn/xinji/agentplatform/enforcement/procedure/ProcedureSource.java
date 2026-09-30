package cn.xinji.agentplatform.enforcement.procedure;

import cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.Calendar;
import cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.CaseSnapshot;

public interface ProcedureSource {
    CaseSnapshot caseSnapshot(String ref, String version);
    Calendar calendar(String ref, String version);
}
