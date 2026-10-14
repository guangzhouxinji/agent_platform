package cn.xinji.agentplatform.adjudication.materials;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import static cn.xinji.agentplatform.adjudication.casework.AdjudicationCaseWork.sha256;
class MaterialCandidateValidatorTest {
    final MaterialCandidateValidator v=new MaterialCandidateValidator();
    Input input(String t){return new Input("s1",MaterialOrganizationModel.Side.APPLICANT,new Source("material-A","v1","page:1",sha256(t),InputKind.PROJECT_AUTHORED),t);}
    String raw(String quote){return "{\"candidates\":[{\"kind\":\"PARTY_ASSERTION\",\"sourceTag\":\"s1\",\"side\":\"APPLICANT\",\"statement\":\"party states\",\"exactQuote\":\""+quote+"\"}]}";}
    @Test void exactSourceVersionAndUtf16LocatorBound(){var i=input("😀申请方称使用弹簧。");var c=v.validate(List.of(i),raw("申请方称使用弹簧")).getFirst();assertEquals(2,c.start());assertEquals(i.text().substring(c.start(),c.end()),c.exactQuote());assertEquals("v1",c.sourceVersion());assertEquals(i.source().sha256(),c.sourceSha256());}
    @Test void inventedQuoteRejected(){assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),raw("卡扣")));}
    @Test void otherSideRejected(){assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),raw("弹簧").replace("APPLICANT","RESPONDENT")));}
    @Test void unknownSourceRejected(){assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),raw("弹簧").replace("s1","s2")));}
    @Test void duplicateQuoteRequiresExactOffset(){assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧-弹簧")),raw("弹簧")));var r=raw("弹簧").replace("}]}",",\"start\":3,\"end\":5}]}");assertEquals(3,v.validate(List.of(input("弹簧-弹簧")),r).getFirst().start());}
    @Test void driftedOffsetRejected(){var r=raw("弹簧").replace("}]}",",\"start\":1,\"end\":3}]}");assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),r));}
    @Test void unknownConfirmationFieldRejected(){var r=raw("弹簧").replace("}]}",",\"confirmedFact\":true}]}");assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),r));}
    @Test void extraRootAndTrailingJsonRejected(){assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),raw("弹簧")+"{}"));assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),raw("弹簧").replace("{\"candidates","{\"finalDecision\":true,\"candidates")));}
    @Test void invalidClassAndEmptyCandidatesRejected(){assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),raw("弹簧").replace("PARTY_ASSERTION","CONFIRMED_FACT")));assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),"{\"candidates\":[]}"));}
    @Test void changedOriginalHashRejected(){var s=new Source("A","v1","p1",sha256("旧原文"),InputKind.PROJECT_AUTHORED);assertThrows(IllegalArgumentException.class,()->v.validate(List.of(new Input("s1",MaterialOrganizationModel.Side.APPLICANT,s,"新原文")),raw("新原文")));}
    @Test void duplicateCandidateRejected(){var r=raw("弹簧");var body=r.substring(r.indexOf("[{")+1,r.lastIndexOf("]"));assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),"{\"candidates\":["+body+","+body+"]}"));}
    @Test void duplicateJsonFieldRejected(){assertThrows(IllegalArgumentException.class,()->v.validate(List.of(input("弹簧")),raw("弹簧").replace("\"sourceTag\":\"s1\"","\"sourceTag\":\"s2\",\"sourceTag\":\"s1\"")));}

}
