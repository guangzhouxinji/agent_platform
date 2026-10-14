package cn.xinji.agentplatform.security.product;
import cn.xinji.agentplatform.consultation.prompt.domain.PromptRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
public class PromptCanonicalProbe {
 public static void main(String[] ignored) {
  var scenarios=Set.of("alpha","beta","gamma","delta","epsilon","zeta","theta");var intents=Set.of("lookup","draft","compare");
  var origin=new PromptRevision.Provenance("source","human",Instant.parse("2026-10-14T00:00:00Z"),"human",Instant.parse("2026-10-14T00:00:00Z"),"CREATE_DRAFT",List.of(),"permission","policy");
  var ref=PromptRevision.referenceFor("00000000-0000-4000-8000-000000000001","00000000-0000-4000-8000-000000000002",1,PromptRevision.Kind.SYSTEM,"Draft","Body",scenarios,intents,"internal","zh",0,List.of());
  var revision=new PromptRevision(ref,PromptRevision.Kind.SYSTEM,"Draft","Body",scenarios,intents,"internal","zh",0,List.of(),origin);
  var json=new ProductComparisonJson(new ObjectMapper().findAndRegisterModules().setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true)).enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
  System.out.println(ref.contentSha256()+" "+json.sha(revision));
 }
}
