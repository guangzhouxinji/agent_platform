package cn.xinji.agentplatform.adjudication.casework;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Only randomly named isolated schemas; never migrates a consultation/enforcement working schema. */
final class CaseApiDatabases implements AutoCloseable {
    final String primaryUrl,authorityUrl,user,password,schema;
    final DriverManagerDataSource primary,authority;
    final JdbcTemplate db,independent;
    private final boolean dm8;
    private boolean primaryCreated,authorityCreated;
    CaseApiDatabases(boolean dm8) throws Exception {
        this.dm8=dm8;String random=UUID.randomUUID().toString().replace("-","").substring(0,14).toUpperCase(Locale.ROOT);
        schema=dm8?"ADJ"+random:"PUBLIC";user=dm8?schema:"sa";password=dm8?"T9"+random+"z":"";
        primaryUrl=dm8?required("ADJ_DM8_PRIMARY_URL"):"jdbc:h2:mem:adj-primary-"+random+";DB_CLOSE_DELAY=-1";
        authorityUrl=dm8?required("ADJ_DM8_AUTHORITY_URL"):"jdbc:h2:mem:adj-authority-"+random+";DB_CLOSE_DELAY=-1";
        if(dm8){
            if(primaryUrl.equals(authorityUrl))throw new IllegalArgumentException("Two independent DM8 instances required");
            create(primaryUrl);primaryCreated=true;create(authorityUrl);authorityCreated=true;
        }
        primary=new DriverManagerDataSource(primaryUrl,user,password);authority=new DriverManagerDataSource(authorityUrl,user,password);
        db=new JdbcTemplate(primary);independent=new JdbcTemplate(authority);
        for(var file:List.of("db/dm8/V032__product_identity.sql","db/dm8/V033__product_oauth2.sql","db/dm8/V046__product_workload_subject.sql","db/dm8/V057__product_oauth_client_control.sql","db/dm8/V086__adjudication_case_work.sql"))script(primary,file);
        for(var file:List.of("db/identity-authority/V001__grant_watermark.sql","db/dm8/adjudication_source_authority.sql"))script(authority,file);
    }
    private void script(DriverManagerDataSource source,String file)throws Exception{try(var connection=source.getConnection()){ScriptUtils.executeSqlScript(connection,new FileSystemResource(Path.of(file)));}}
    private void create(String url)throws Exception{
        if(!schema.matches("ADJ[A-F0-9]{14}"))throw new IllegalStateException("Unsafe isolated schema");
        try(var connection=DriverManager.getConnection(url,required(url.equals(authorityUrl)?"ADJ_DM8_AUTHORITY_ADMIN_USER":"ADJ_DM8_ADMIN_USER"),required(url.equals(authorityUrl)?"ADJ_DM8_AUTHORITY_ADMIN_PASSWORD":"ADJ_DM8_ADMIN_PASSWORD"));var sql=connection.createStatement()){
            sql.execute("CREATE USER "+schema+" IDENTIFIED BY \""+password+"\"");sql.execute("GRANT RESOURCE TO "+schema);
        }
    }
    static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("Missing isolated deployment setting "+key);return value;}
    @Override public void close()throws Exception{
        if(dm8){if(authorityCreated)drop(authorityUrl);if(primaryCreated)drop(primaryUrl);}
        else{db.execute("SHUTDOWN");independent.execute("SHUTDOWN");}
    }
    private void drop(String url)throws Exception{
        if(!schema.matches("ADJ[A-F0-9]{14}"))throw new IllegalStateException("Unsafe isolated cleanup");
        try(var connection=DriverManager.getConnection(url,required(url.equals(authorityUrl)?"ADJ_DM8_AUTHORITY_ADMIN_USER":"ADJ_DM8_ADMIN_USER"),required(url.equals(authorityUrl)?"ADJ_DM8_AUTHORITY_ADMIN_PASSWORD":"ADJ_DM8_ADMIN_PASSWORD"));var sql=connection.createStatement()){
            sql.execute("DROP USER "+schema+" CASCADE");
        }
    }
}
