package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import java.util.List;
import java.util.Set;

/** Page-facing current/history representation. An unreadable material never serializes its body. */
public record CaseWorkView(long version,long inputVersion,CaseInput caseInput,List<MaterialView> materials,
                           List<Claim> claims,List<Finding> findings,List<Citation> citations,List<ReportView> reports,
                           Set<String> inputContributors,List<Audit> audits) {
    public record MaterialView(String id,String caseId,Side side,String submitter,String content,
                               String assertedPurpose,Source source,boolean readable,String supersedesMaterialId) { }
    public record ReportView(Report report,boolean stale,boolean current,boolean policyStale) { }
    public static ReportView report(State state,Report r,String policy){boolean policyStale=policy!=null&&!policy.equals(r.policyVersion());boolean stale=state.stale(r)||policyStale;return new ReportView(r,stale,r.revision()==state.reports().size()&&!stale,policyStale);}
    public static CaseWorkView of(State state) {return of(state,null);}
    public static CaseWorkView of(State state,String policy) {
        return new CaseWorkView(state.version(),state.inputVersion(),state.caseInput(),state.materials().stream().map(m->
                new MaterialView(m.id(),m.caseId(),m.side(),m.submitter(),m.readable()?m.content():null,m.assertedPurpose(),m.source(),m.readable(),m.supersedesMaterialId())).toList(),
                state.claims(),state.findings(),state.citations(),state.reports().stream().map(r->report(state,r,policy)).toList(),state.inputContributors(),state.audits());
    }
}
