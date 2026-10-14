import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.management.ThreadMXBean;
import com.zaxxer.hikari.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current real authorization history, read-only ABBA component test. Never an HTTP/SLO substitute. */
public final class MeasureConsultationFramingCandidate {
    record Authority(long generation,String state,String intent,String sha) { }
    record Sample(int ordinal,double elapsedMs,long allocatedBytes) { }
    static final class ProductLoader extends URLClassLoader {
        ProductLoader(URL[] urls) { super(urls,MeasureConsultationFramingCandidate.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
            if(!name.startsWith("cn.xinji.agentplatform.security.product.")) return super.loadClass(name,resolve);
            synchronized(getClassLoadingLock(name)) {
                Class<?> loaded=findLoadedClass(name);
                if(loaded==null)loaded=findClass(name);
                if(resolve)resolveClass(loaded);return loaded;
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length<2||args.length>3||(args.length==3&&!"BAAB".equals(args[2])))
            throw new IllegalArgumentException("Sealed product class root, isolated candidate root and optional BAAB order required");
        URL sealed=Path.of(args[0]).toUri().toURL(),candidate=Path.of(args[1]).toUri().toURL();
        var mapper=new ObjectMapper();
        try(var base=new ProductLoader(new URL[]{sealed});var trial=new ProductLoader(new URL[]{candidate,sealed})) {
            int cases=conformance(base,trial);
            var config=new HikariConfig();
            config.setJdbcUrl("jdbc:dm://127.0.0.1:5301");config.setUsername("CONPROD");
            config.setPassword(required("FRAME_PRIMARY_PASSWORD"));config.setSchema("CONPROD");
            config.setReadOnly(true);config.setMaximumPoolSize(10);config.setMinimumIdle(10);
            config.setPoolName("consultation-framing-readonly-candidate");
            try(var pool=new HikariDataSource(config);
                    var authority=DriverManager.getConnection("jdbc:dm://127.0.0.1:5302","CONAUTH_SVC",required("FRAME_AUTHORITY_PASSWORD"));
                    var jobs=Executors.newFixedThreadPool(10)) {
                authority.setSchema("CONAUTH");authority.setReadOnly(true);
                Authority expected=state(authority);
                if(!"READY".equals(expected.state())||expected.intent()!=null)
                    throw new IllegalStateException("Independent authority is pending; no benchmark allowed");
                var db=new JdbcTemplate(pool);db.setFetchSize(256);
                Method baseline=digestMethod(base),variant=digestMethod(trial);
                if(!expected.sha().equals(invoke(baseline,db))||!expected.sha().equals(invoke(variant,db)))
                    throw new IllegalStateException("Sealed or candidate digest differs from live independent authority");
                var bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();
                if(!bean.isThreadAllocatedMemorySupported())throw new IllegalStateException("Allocation counter unavailable");
                bean.setThreadAllocatedMemoryEnabled(true);
                int phase=0;
                boolean[] sequence=args.length==3?new boolean[]{true,false,false,true}:new boolean[]{false,true,true,false};
                for(boolean useCandidate:sequence) {
                    if(!expected.equals(state(authority)))throw new IllegalStateException("Authority changed before phase");
                    Method method=useCandidate?variant:baseline;
                    List<Callable<Sample>> calls=new ArrayList<>();
                    for(int i=0;i<100;i++) {
                        int ordinal=i;
                        calls.add(()->{
                            long thread=Thread.currentThread().threadId(),beforeBytes=bean.getThreadAllocatedBytes(thread);
                            long start=System.nanoTime();String result=invoke(method,db);
                            double ms=(System.nanoTime()-start)/1e6;
                            long bytes=bean.getThreadAllocatedBytes(thread)-beforeBytes;
                            if(!expected.sha().equals(result))throw new IllegalStateException("Canonical digest changed");
                            return new Sample(ordinal,ms,bytes);
                        });
                    }
                    List<Sample> samples=new ArrayList<>();
                    for(var result:jobs.invokeAll(calls))samples.add(result.get());
                    if(!expected.equals(state(authority)))throw new IllegalStateException("Authority changed during phase");
                    var latencies=samples.stream().map(Sample::elapsedMs).sorted().toList();
                    System.out.println(mapper.writeValueAsString(Map.of("schema","READ_ONLY_GRANT_FRAMING_COMPONENT/v1",
                            "phase",phase++,"mode",useCandidate?"ASCII_SCRATCH":"SEALED_BASELINE",
                            "runs",100,"concurrency",10,"p95Ms",latencies.get(94),
                            "meanMs",latencies.stream().mapToDouble(v->v).average().orElseThrow(),
                            "meanAllocatedBytes",samples.stream().mapToLong(Sample::allocatedBytes).average().orElseThrow(),
                            "authorityGeneration",expected.generation(),"samples",samples)));
                }
                System.out.println(mapper.writeValueAsString(Map.of("schema","READ_ONLY_GRANT_FRAMING_SUMMARY/v1",
                        "conformanceCases",cases,"allActualHistoryDigestsMatch",true,"sha",expected.sha(),
                        "databaseWrites",0,"modelCalls",0,"httpSloAcceptance",false,
                        "noPermissionOrHistoryCheckSkipped",true,"formalRuntimeUnchanged",true,
                        "limitations",List.of("Component test in pooled local JVM, not 8CPU/4GiB product HTTP",
                                "ABBA controls order only partly; no performance claim from allocation count alone"))));
            }
        }
    }
    static String required(String name) {
        String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("Managed credential unavailable");
        return value;
    }
    static Authority state(Connection db) throws SQLException {
        try(var query=db.prepareStatement("SELECT generation,state,intent_id,history_sha FROM id2_authority_watermark WHERE issuer=?")) {
            query.setString(1,"https://localhost:5443");try(var rows=query.executeQuery()) {
                if(!rows.next())throw new IllegalStateException("Independent authority absent");
                var result=new Authority(rows.getLong(1),rows.getString(2),rows.getString(3),rows.getString(4));
                if(rows.next())throw new IllegalStateException("Independent authority ambiguous");return result;
            }
        }
    }
    static Method digestMethod(ClassLoader loader) throws Exception {
        var method=loader.loadClass("cn.xinji.agentplatform.security.product.ProductGrantHistoryBatch")
                .getDeclaredMethod("digest",JdbcTemplate.class,String.class,boolean.class,boolean.class);
        method.setAccessible(true);return method;
    }
    static String invoke(Method method,JdbcTemplate db) throws Exception {
        return (String)method.invoke(null,db,"https://localhost:5443",true,true);
    }
    static int conformance(ClassLoader base,ClassLoader candidate) throws Exception {
        List<String[]> cases=new ArrayList<>();
        cases.add(new String[]{null,"",null,"-1",":"});
        cases.add(new String[]{"a".repeat(2048),"z","a".repeat(2049),"\u0000"});
        cases.add(new String[]{"公开检索", "é", "\ud83d\ude00", "e\u0301", "\ud800", "\udc00"});
        var random=new Random(20261004L);
        for(int n=0;n<100;n++) {
            char[] text=new char[random.nextInt(3000)];
            for(int i=0;i<text.length;i++)text[i]=(char)random.nextInt(65536);
            cases.add(new String[]{"previous-ascii",new String(text),"short",null});
        }
        for(var values:cases) {
            String expected=reference(values);
            if(!expected.equals(frame(base,values))||!expected.equals(frame(candidate,values)))
                throw new IllegalStateException("Framing contract changed on Unicode, null or reused scratch boundary");
        }
        return cases.size();
    }
    static String reference(String[] values) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");
        for(String value:values) {
            hash.update((value==null?"-1:":value.length()+":").getBytes(StandardCharsets.UTF_8));
            if(value!=null)hash.update(value.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    static String frame(ClassLoader loader,String[] values) throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");
        var type=loader.loadClass("cn.xinji.agentplatform.security.product.ProductHistoryDigestFraming");
        var constructor=type.getDeclaredConstructor(MessageDigest.class);constructor.setAccessible(true);
        Object instance=constructor.newInstance(hash);
        Method add=type.getDeclaredMethod("add",String.class);add.setAccessible(true);
        for(String value:values)add.invoke(instance,(Object)value);
        return HexFormat.of().formatHex(hash.digest());
    }
}
