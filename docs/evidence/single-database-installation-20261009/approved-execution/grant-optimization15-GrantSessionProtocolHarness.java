import java.sql.*;import java.lang.reflect.*;import java.util.*;
/** Engineering-only protocol benchmark; never exposes a database URL or product route. */
public class GrantSessionProtocolHarness {
 public static void main(String[] args)throws Exception {
  int[] count={0,0};
  int result=SingleDatabaseGrantSessionJdbc.run(System.in,System.out,()->{count[0]++;return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,arguments)->{if(method.getName().equals("getAutoCommit"))return true;if(method.getName().equals("close")){count[1]++;return null;}throw new UnsupportedOperationException();});},(connection,request)->{if(request.path("mode").asText().equals("fail"))throw new SQLException("PRIVATE_DRIVER_SENTINEL","08006",123);return Map.of("actualJdbc",false,"benchmark","PROTOCOL_ONLY_NO_DATABASE", "unicode","原始权项");});
  System.err.println("CONNECTION_FACTORY_CALLS="+count[0]+";CLOSES="+count[1]);System.exit(result);
 }
}
