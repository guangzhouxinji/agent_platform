import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
/** Bounded process protocol; each operation reauthenticates and closes its own connection. */
public final class SingleDatabaseGrantSessionJdbc {
 interface ConnectionFactory { Connection open() throws Exception; }
 interface Operation { Map<String,Object> apply(Connection c,JsonNode request) throws Exception; }
 static int run(InputStream input,OutputStream output,ConnectionFactory factory,Operation operation)throws Exception {
  var in=new BufferedInputStream(input);var out=new PrintWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));long expected=1;
  for(;;){var line=new ByteArrayOutputStream();int b;while((b=in.read())!=-1&&b!='\n'){if(line.size()>=8*1024*1024) return 1;line.write(b);}if(b==-1){return line.size()==0?0:1;}
   long id=expected;try{JsonNode envelope=SingleDatabaseLifecycleJdbc.JSON.readTree(line.toByteArray());if(envelope==null||!envelope.isObject()||envelope.size()!=2||!envelope.has("requestId")||!envelope.get("requestId").isIntegralNumber()||envelope.get("requestId").asLong()!=expected||!envelope.has("request"))throw new IllegalArgumentException();
    Map<String,Object> result;try(Connection connection=factory.open()){if(!connection.getAutoCommit())throw new IllegalArgumentException();result=operation.apply(connection,envelope.get("request"));}String encoded=SingleDatabaseLifecycleJdbc.JSON.writeValueAsString(Map.of("requestId",id,"result",result));if(encoded.getBytes(StandardCharsets.UTF_8).length+1>32*1024*1024)throw new IllegalArgumentException();out.println(encoded);out.flush();if(out.checkError())return 1;expected++;
   }catch(Exception e){String state=e instanceof SQLException?((SQLException)e).getSQLState():"GUARD";if(state==null||!state.matches("[A-Z0-9]{5}"))state="UNKNOWN";out.println(SingleDatabaseLifecycleJdbc.JSON.writeValueAsString(Map.of("requestId",id,"error","JDBC_OPERATION_REFUSED_OR_FAILED","sqlState",state,"vendorCode",e instanceof SQLException?((SQLException)e).getErrorCode():0)));out.flush();return 1;}
  }
 }
 public static void main(String[] args){int exit=1;try{String url=System.getenv("LIFECYCLE_JDBC_URL"),user=System.getenv("LIFECYCLE_JDBC_USER"),password=System.getenv("LIFECYCLE_JDBC_PASSWORD");if(args.length!=0||url==null||!url.matches("jdbc:dm://[A-Za-z0-9.-]+:[0-9]{1,5}")||user==null||!user.matches("[A-Z][A-Z0-9_]{0,63}")||password==null)throw new IllegalArgumentException();var expiry=java.time.OffsetDateTime.parse(System.getenv("LIFECYCLE_GRANT_EXPIRES_AT")).toInstant();exit=run(System.in,System.out,()->{if(!java.time.Instant.now().isBefore(expiry))throw new IllegalArgumentException();return DriverManager.getConnection(url,user,password);},(connection,request)->{String action=request.path("action").asText();if(!java.time.Instant.now().isBefore(expiry)||!url.equals(connection.getMetaData().getURL())||!user.equalsIgnoreCase(connection.getMetaData().getUserName())||!(action.equals("inventory")&&user.equals("SYSDBA")||action.equals("grant-object")&&!user.equals("SYSDBA")&&user.equals(request.path("schema").asText())))throw new IllegalArgumentException();return SingleDatabaseLifecycleJdbc.perform(connection,request);});}catch(Exception ignored){System.out.println("{\"error\":\"GRANT_SESSION_START_REFUSED\"}");}System.exit(exit);}
}
