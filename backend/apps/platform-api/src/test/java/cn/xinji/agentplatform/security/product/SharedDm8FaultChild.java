package cn.xinji.agentplatform.security.product;
import java.nio.file.*;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
/** Barrier is observation only; the parent kills this actual JVM before a product stage can finish. */
public final class SharedDm8FaultChild {
    static String env(String key){String v=System.getenv(key);if(v==null)throw new IllegalStateException("Owned child configuration absent");return v;}
    static void pause(String stage,Path marker) {
        try{Files.writeString(marker,stage,StandardOpenOption.CREATE_NEW);Thread.sleep(120000);throw new IllegalStateException("Owned crash was not injected within bound");}
        catch(java.io.IOException|InterruptedException e){throw new IllegalStateException("Owned barrier failed",e);}
    }
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out,true,java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err,true,java.nio.charset.StandardCharsets.UTF_8));
        String stage=args[0];Path marker=Path.of(args[1]);
        try(var p=new SharedDm8FaultPools(env("OWNED_FAULT_URL"),env("OWNED_FAULT_MAIN_SCHEMA"),env("OWNED_FAULT_MAIN_USER"),env("OWNED_FAULT_AUTH_SCHEMA"),env("OWNED_FAULT_AUTH_USER"),env("OWNED_FAULT_SECRET"))) {
            var observed=new JdbcTemplate(p.auth) {
                @Override public int update(String sql,Object... values) {
                    if(stage.endsWith("-committed")&&(sql.startsWith("UPDATE id4_source_watermark SET source_epoch=")||sql.startsWith("UPDATE id2_authority_watermark SET generation=")))pause(stage,marker);
                    return super.update(sql,values);
                }
            };
            if(stage.startsWith("source-")) {
                var wm=new ProductSourceWatermark(p.primary,observed,p.tx);
                wm.change(SharedDm8FaultSourceInput.SOURCE,intent->{
                    if(stage.equals("source-pending"))pause(stage,marker);
                    if(stage.equals("source-uncommitted")){SharedDm8FaultSourceInput.contract(p.primary,Instant.now());pause(stage,marker);}
                    SharedDm8FaultSourceInput.admit(p.primary,Instant.now(),intent);return null;
                });
            }else if(stage.startsWith("grant-")) {
                new ProductGrantWatermark(p.primary,observed,SharedDm8FaultFixture.ISSUER).change(()->p.tx.execute(status->{
                    p.primary.update("UPDATE id2_subject SET status='DISABLED',credential_version=2,disabled_at=CURRENT_TIMESTAMP WHERE issuer=? AND subject_id='owned-member'",SharedDm8FaultFixture.ISSUER);
                    p.primary.update("INSERT INTO id2_subject_event VALUES(?,?,?,'DISABLE',1,2,?,?)",UUID.randomUUID().toString(),SharedDm8FaultFixture.ISSUER,"owned-member","owned-coordination-engineer",Timestamp.from(Instant.now()));
                    if(stage.equals("grant-uncommitted"))pause(stage,marker);return null;
                }));
            }else throw new IllegalArgumentException("Unknown owned fault stage");
            throw new IllegalStateException("Expected stage did not stop");
        }
    }
}
