package cn.xinji.agentplatform.runtime;

import java.sql.DriverManager;
import java.util.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit real-DM8 verification entry; always creates and drops only its own random schema. */
class AgentCheckpointDm8Verification extends AgentCheckpointPersistenceTest {
    String schema,url,admin,password;
    boolean owned;
    @Override DriverManagerDataSource newSource() throws Exception {
        url=required("AGENT_DM8_URL");admin=required("AGENT_DM8_ADMIN_USER");password=required("AGENT_DM8_ADMIN_PASSWORD");
        schema="ARV"+UUID.randomUUID().toString().replace("-","").substring(0,14).toUpperCase(Locale.ROOT);
        String credential="T9"+UUID.randomUUID().toString().replace("-","")+"z";
        validate();
        try(var c=DriverManager.getConnection(url,admin,password);var sql=c.createStatement()) {
            sql.execute("CREATE USER "+schema+" IDENTIFIED BY \""+credential+"\"");owned=true;
            sql.execute("GRANT RESOURCE TO "+schema);
        }
        return new DriverManagerDataSource(url,schema,credential);
    }
    @Override void cleanup() throws Exception {
        if(!owned)return;validate();
        try(var c=DriverManager.getConnection(url,admin,password);var sql=c.createStatement()) {sql.execute("DROP USER "+schema+" CASCADE");owned=false;}
    }
    private void validate(){if(schema==null || !schema.matches("ARV[A-F0-9]{14}"))throw new IllegalStateException("UNOWNED_SCHEMA");}
    private String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing "+name);return value;}
}
