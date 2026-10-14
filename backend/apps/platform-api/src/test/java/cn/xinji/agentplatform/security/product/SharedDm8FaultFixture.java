package cn.xinji.agentplatform.security.product;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
final class SharedDm8FaultFixture implements AutoCloseable {
    static final String ISSUER="https://owned-coordination.example.org";
    final String url,mainSchema,authSchema,mainUser,authUser,secret;
    final JdbcTemplate admin;
    final List<String> created=new ArrayList<>();
    SharedDm8FaultPools pools;
    Process child;
    boolean success;
    SharedDm8FaultFixture() throws Exception {
        url=System.getenv("CON_SHARED_FAULT_DM8_URL");String user=System.getenv("CON_SHARED_FAULT_DM8_USER");String password=System.getenv("CON_SHARED_FAULT_DM8_PASSWORD");
        Assumptions.assumeTrue(url!=null&&user!=null&&password!=null,"Explicit owned shared-instance fault DM8 environment required");
        if(!url.equals("jdbc:dm://127.0.0.1:5492"))throw new IllegalArgumentException("Exact owned sequential resource required");
        String tag=UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT);
        mainSchema="CFM"+tag;authSchema="CFA"+tag;mainUser="CFS"+tag;authUser="CFT"+tag;secret="T9a"+UUID.randomUUID().toString().replace("-","")+"Z";
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        for(String name:List.of(mainSchema,authSchema,mainUser,authUser)) {
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM DBA_USERS WHERE USERNAME=?",Integer.class,name)).isZero();
            admin.execute("CREATE USER "+name+" IDENTIFIED BY \""+secret+"\"");created.add(name);
        }
        admin.execute("GRANT RESOURCE TO "+mainSchema);admin.execute("GRANT RESOURCE TO "+authSchema);
        var ownerMain=new DriverManagerDataSource(url,mainSchema,secret);var ownerAuth=new DriverManagerDataSource(url,authSchema,secret);
        for(String file:List.of("V032__product_identity.sql","V036__product_source_candidates.sql","V037__product_source_admission.sql","V039__product_source_verification.sql","V053__product_source_use_decision.sql"))script(ownerMain,"db/dm8/"+file);
        for(String file:List.of("V001__grant_watermark.sql","V003__consultation_source_watermark.sql","V004__consultation_source_recovery.sql"))script(ownerAuth,"db/identity-authority/"+file);
        var mainOwner=new JdbcTemplate(ownerMain);var authOwner=new JdbcTemplate(ownerAuth);
        for(String table:mainOwner.queryForList("SELECT TABLE_NAME FROM USER_TABLES",String.class)) mainOwner.execute("GRANT SELECT ON "+table+" TO "+mainUser);
        for(String table:List.of("id2_subject","id2_subject_event","con4_source_contract","con4_source_admission","con4_source_field_use","con4_source_current","con4_source_authority_event","con4_source_product_decision"))mainOwner.execute("GRANT INSERT,UPDATE ON "+table+" TO "+mainUser);
        for(String table:List.of("id2_subject","con4_source_contract","con4_source_admission"))mainOwner.execute("GRANT REFERENCES ON "+table+" TO "+mainUser);
        authOwner.execute("GRANT SELECT,UPDATE ON id2_authority_watermark TO "+authUser);
        authOwner.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON id4_source_watermark TO "+authUser);
        authOwner.execute("GRANT SELECT,INSERT ON id4_source_recovery_event TO "+authUser);
        authOwner.update("INSERT INTO id2_authority_watermark VALUES(?,0,'READY',NULL,?)",ISSUER,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        mainOwner.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,'owned-member','owned-member','not-a-login','ACTIVE',1,CURRENT_TIMESTAMP)",ISSUER);
        reconnect();
        assertThatThrownBy(()->pools.primary.queryForObject("SELECT COUNT(*) FROM "+authSchema+".id2_authority_watermark",Integer.class)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->pools.authority.queryForObject("SELECT COUNT(*) FROM "+mainSchema+".id2_subject",Integer.class)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        try(var c=pools.primary.getDataSource().getConnection()){assertThat(c.getMetaData().getUserName()).isEqualToIgnoringCase(mainUser);}
        try(var c=pools.authority.getDataSource().getConnection()){assertThat(c.getMetaData().getUserName()).isEqualToIgnoringCase(authUser);}
    }
    static void script(javax.sql.DataSource ds,String ref)throws Exception {try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource(ref));}}
    void reconnect(){if(pools!=null)pools.close();pools=new SharedDm8FaultPools(url,mainSchema,mainUser,authSchema,authUser,secret);}
    void crash(String stage,Path marker) throws Exception {
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-Xmx128m","-cp",System.getProperty("java.class.path"),SharedDm8FaultChild.class.getName(),stage,marker.toAbsolutePath().toString());
        var env=command.environment();for(String key:List.of("CON_SHARED_FAULT_DM8_PASSWORD","DM8_JDBC_PASSWORD"))env.remove(key);
        env.put("OWNED_FAULT_URL",url);env.put("OWNED_FAULT_MAIN_SCHEMA",mainSchema);env.put("OWNED_FAULT_AUTH_SCHEMA",authSchema);env.put("OWNED_FAULT_MAIN_USER",mainUser);env.put("OWNED_FAULT_AUTH_USER",authUser);env.put("OWNED_FAULT_SECRET",secret);
        command.redirectOutput(marker.resolveSibling("child-stdout.log").toFile());command.redirectError(marker.resolveSibling("child-stderr.log").toFile());
        child=command.start();System.out.println("OWNED_CHILD_RAW_DIRECTORY "+marker.getParent().toAbsolutePath());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(!Files.exists(marker)&&child.isAlive()&&System.nanoTime()<until)Thread.sleep(50);
        if(!Files.exists(marker)&&Files.exists(marker.resolveSibling("child-stderr.log")))System.out.println(Files.readString(marker.resolveSibling("child-stderr.log")).replace(secret,"[OWNED_SECRET_REDACTED]"));
        assertThat(Files.exists(marker)).as("actual product PENDING/stage barrier reached; raw child logs retained privately").isTrue();
        assertThat(Files.readString(marker)).isEqualTo(stage);child.destroyForcibly();assertThat(child.waitFor(15,TimeUnit.SECONDS)).isTrue();assertThat(child.exitValue()).isNotZero();
        reconnect();System.out.println("OWNED_CHILD_DEATH stage="+stage+" pid="+child.pid()+" exactFreshUsers="+mainSchema+","+authSchema+" reconnected=true");
    }
    void complete(){success=true;}
    @Override public void close()throws Exception {
        if(child!=null&&child.isAlive()){child.destroyForcibly();child.waitFor(15,TimeUnit.SECONDS);}if(pools!=null)pools.close();
        if(success){for(String name:List.of(mainUser,authUser,mainSchema,authSchema))if(created.contains(name))admin.execute("DROP USER "+name+" CASCADE");}
        else System.out.println("OWNED_FAILED_CONTEXT_PRESERVED "+mainSchema+","+authSchema);
    }
}
