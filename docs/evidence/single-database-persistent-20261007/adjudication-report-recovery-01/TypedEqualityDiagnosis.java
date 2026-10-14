package cn.xinji.agentplatform.adjudication.casework;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
public final class TypedEqualityDiagnosis {
 static List<Map<String,Object>> differences=new ArrayList<>();
 static void diff(String path,JsonNode a,JsonNode b){if(a.equals(b))return;if(a.isObject()&&b.isObject()){a.fieldNames().forEachRemaining(k->{if(b.has(k))diff(path+"/"+k,a.get(k),b.get(k));else differences.add(Map.of("path",path+"/"+k,"missing",true));});}else if(a.isArray()&&b.isArray()&&a.size()==b.size()){for(int i=0;i<a.size();i++)diff(path+"/"+i,a.get(i),b.get(i));}else if(differences.size()<20){differences.add(Map.of("path",path,"typedNodeClass",a.getClass().getSimpleName(),"parsedNodeClass",b.getClass().getSimpleName(),"typedNode",a.toString(),"parsedNode",b.toString()));}}
 public static void main(String[] args)throws Exception{
  Path out=Path.of(args[0]);var mapper=AdjudicationPersistentHarness.JSON;String raw=Files.readString(out.resolve("product-active-state.json"));var codec=AdjudicationPersistentHarness.CODEC;
  var typed=codec.decode(raw,CaseWorkContract.State.class);var typedNode=mapper.valueToTree(typed);var parsed=mapper.readTree(raw);diff("",typedNode,parsed);
  var a=codec.decode(codec.encode(typed),CaseWorkContract.State.class);boolean typedExact=typed.equals(a);
  var result=new LinkedHashMap<String,Object>();result.put("sameOriginalBytesUsed",true);result.put("originalJsonNodeAssertionResult",typedNode.equals(parsed));result.put("completeTypedStateEqualsCanonicalRoundtrip",typedExact);result.put("differences",differences);result.put("noDatabaseContact",true);result.put("explanation","Original JsonNode comparison can fail solely due to DecimalNode vs DoubleNode numeric representation of Instant; successor compares complete typed records, not a subset. Database row digests separately prove persistence.");
  var classes=new ArrayList<Map<String,Object>>();for(Class<?> c:List.of(CaseWorkCommands.class,JdbcCaseWorkRepository.class,CaseSourceAuthority.class,CaseWorkCodec.class,CaseWorkContract.class,AdjudicationCaseWork.class))classes.add(Map.of("class",c.getName(),"codeSource",c.getProtectionDomain().getCodeSource().getLocation().toString()));result.put("currentProductClassesLoadedFrom",classes);
  Files.write(out.resolve("typed-equality-diagnosis.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);System.out.println("same-bytes JsonNode equality="+typedNode.equals(parsed)+", complete typed equality="+typedExact+", differences="+differences.size());if(!typedExact)System.exit(2);
 }
}
