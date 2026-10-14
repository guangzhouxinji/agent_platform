package cn.xinji.agentplatform.enforcement.penalty;

import java.sql.DriverManager;
import java.util.Properties;

/** Infrastructure readiness only. SELECT 1 is explicitly not product I or acceptance evidence. */
public final class PenaltyDm8Readiness {
    public static void main(String[] args) throws Exception {
        String url=required("ENF_ACCEPTANCE_BUSINESS_URL");if(!url.startsWith("jdbc:dm://"))throw new IllegalStateException("Real DM8 required");
        Class.forName("dm.jdbc.driver.DmDriver");DriverManager.setLoginTimeout(2);
        var properties=new Properties();properties.setProperty("user",required("ENF_ACCEPTANCE_BUSINESS_USER"));properties.setProperty("password",required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        try {
            for(int attempt=0;attempt<90;attempt++) {
                try(var connection=DriverManager.getConnection(url,properties);var statement=connection.createStatement()) {
                    statement.setQueryTimeout(2);try(var result=statement.executeQuery("SELECT 1 FROM DUAL")){if(result.next()&&result.getInt(1)==1){System.out.println("REAL_DM8_SQL_READY_INFRA_ONLY_NOT_PRODUCT_I");return;}}
                } catch(java.sql.SQLException unavailable) { /* No secret-bearing exception text is logged. */ }
                Thread.sleep(500);
            }
            throw new IllegalStateException("Real DM8 SQL readiness timeout; product I not run");
        } finally {properties.clear();}
    }
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}
