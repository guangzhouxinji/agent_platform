import cn.xinji.agentplatform.runtime.persistence.AgentCheckpointCodec;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
/** Offline only: decrypt this test's self-owned snapshots with its engineering key; no DB access. */
public class InspectOwnRunReceipts {
 public static void main(String[] args) throws Exception {
  var json=new ObjectMapper().findAndRegisterModules();var codec=new AgentCheckpointCodec(json,Base64.getEncoder().encodeToString(new byte[32]));
  var output=json.createArrayNode();
  for(var path:Files.list(Path.of(args[0])).filter(p->p.getFileName().toString().endsWith("-after.json")).toList()) {
   JsonNode tables=json.readTree(path.toFile());var heads=tables.path("AGENT_RUN_HEAD");
   for(var head:heads) {
    String run=head.get(0).asText(),agent=head.get(1).asText(),task=head.get(2).asText();long version=head.get(6).asLong();
    for(var row:tables.path("AGENT_RUN_REVISION")) if(row.get(0).asText().equals(run)&&row.get(1).asLong()==version) {
     var c=codec.decode(run,agent,task,version,new AgentCheckpointCodec.Encoded(row.get(2).asText(),row.get(3).asText(),row.get(4).asText())).checkpoint();
     var receipt=output.addObject().put("scenario",path.getFileName().toString()).put("runId",run).put("revision",version).put("phase",c.phase().name());
     receipt.set("outcome",json.valueToTree(c.outcome()));receipt.set("observations",json.valueToTree(c.observations()));
    }
   }
  }
  Files.writeString(Path.of(args[1]),json.writerWithDefaultPrettyPrinter().writeValueAsString(output));System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
 }
}
