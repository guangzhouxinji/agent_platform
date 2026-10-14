import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
public class HumanCanonicalProbe {
  public static void main(String[] args) throws Exception {
    var mapper=new ObjectMapper();
    String[] reasons={"a"+(char)31+"b", "a\\u001fb", "a"+(char)11+(char)14+(char)15+(char)26+(char)27+(char)28+(char)29+(char)30+(char)31+"\b\t\n\f\r"+"b", "emoji:"+new String(Character.toChars(0x1f600)), "a"+(char)0xd800+"b"};
    for(int i=0;i<reasons.length;i++) {
      var actor=new TreeMap<String,Object>();actor.put("issuer","https://product.example");actor.put("kind","HUMAN");actor.put("subject","member");
      var command=new TreeMap<String,Object>();command.put("comparison",null);command.put("expectedTaskVersion",7);command.put("materials",java.util.List.of());command.put("purpose","ORDINARY_ASSISTANCE");command.put("reason",reasons[i]);
      var root=new TreeMap<String,Object>();root.put("actor",actor);root.put("command",command);root.put("requestId","key");root.put("taskId","task");
      String canonical=mapper.writeValueAsString(root);byte[] bytes=canonical.getBytes(StandardCharsets.UTF_8);
      System.out.println(i+" "+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))+" "+HexFormat.of().formatHex(bytes));
    }
  }
}
