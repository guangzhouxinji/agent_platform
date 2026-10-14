import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage;
import static cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import java.util.*;

/** Isolated diagnostic using current compiled domain; no Task, DB, rights or model operations. */
class LineageTwoCorrectionsProbe {
    static String sha(String c){return c.repeat(64);}
    public static void main(String[] args){
        var facts=new HashMap<FactKey,String>();
        for(var t:List.of(Type.TARGET,Type.GOAL,Type.PURPOSE,Type.METHOD,Type.CONFIGURATION))facts.put(new FactKey(t,"global"),sha("a"));
        facts.put(new FactKey(Type.FEATURE,"F1"),sha("a"));
        facts.put(new FactKey(Type.CANDIDATE,"A"),sha("a"));facts.put(new FactKey(Type.CANDIDATE,"B"),sha("a"));
        var a=new RowKey("A",List.of(1),"F1");var b=new RowKey("B",List.of(1),"F1");
        var original=new Snapshot("task","original",sha("1"),null,facts,Set.of(a,b));
        var changed=new HashMap<>(facts);changed.put(new FactKey(Type.CANDIDATE,"B"),sha("b"));
        var parent=new Snapshot("task","parent",sha("2"),"original",changed,Set.of(a,b));
        var first=PatentComparisonLineage.plan(original,parent,List.of(report(original,a),report(original,b)));
        var changedAgain=new HashMap<>(changed);changedAgain.put(new FactKey(Type.CANDIDATE,"B"),sha("c"));
        var current=new Snapshot("task","current",sha("3"),"parent",changedAgain,Set.of(a,b));
        // Current adapter enumerates direct-parent native reports: B was recomputed, A only referenced the original.
        var second=PatentComparisonLineage.plan(parent,current,List.of(report(parent,b)));
        boolean firstRetainedA=first.carried().stream().anyMatch(r->r.key().equals(a));
        boolean secondRecomputesA=second.recompute().contains(a);
        if(!firstRetainedA||!secondRecomputesA||!second.recompute().contains(b))throw new IllegalStateException("HYPOTHESIS_NOT_REPRODUCED");
        System.out.println("{\"scope\":\"CURRENT_DOMAIN_DIAGNOSTIC_NOT_RUNTIME\",\"onlyBChangedTwice\":true,\"firstRetainedA\":"+firstRetainedA+",\"secondIncorrectlyPendingA\":"+secondRecomputesA+",\"productSourceGapConfirmed\":true,\"productionChanges\":0}");
    }
    static SourceReport report(Snapshot input,RowKey row){
        var dependencies=new HashSet<FactKey>();
        for(var key:input.facts().keySet())if(key.type()!=Type.CANDIDATE||key.id().equals(row.candidateId()))dependencies.add(key);
        return new SourceReport(input.comparisonId(),input.inputSha(),row.candidateId(),"report-"+input.comparisonId()+"-"+row.candidateId(),sha("d"),FULL_FEATURE_CONTEXT,
                List.of(new RetainedRow(row,sha("e"),dependencies)));
    }
}
