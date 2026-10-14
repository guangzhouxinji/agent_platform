package cn.xinji.agentplatform.security.product;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Unique test fixture: engineering schema/data and disposable private CA. No production credentials. */
final class ProductComparisonStartMtlsEngineeringSupport {
    static final String TASK="11111111-1111-1111-1111-111111111111", COMPARISON="22222222-2222-2222-2222-222222222222";
    static final String CANDIDATE="c".repeat(64), SHA="a".repeat(64), REQUEST="b".repeat(64);
    static final Instant NOW=Instant.parse("2026-10-07T08:00:00Z"), UNTIL=NOW.plusSeconds(60);
    final JdbcTemplate db;
    final TransactionTemplate tx;
    final String url;
    ProductComparisonStartMtlsEngineeringSupport(Path databaseFile) throws Exception {
        url="jdbc:h2:file:"+databaseFile.toAbsolutePath().toString().replace('\\','/')+";MODE=Oracle;LOCK_TIMEOUT=10000";
        var ds=new DriverManagerDataSource(url,"sa","");
        db=new JdbcTemplate(ds); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE con3_task(task_id VARCHAR(36) PRIMARY KEY, version BIGINT, authorization_version BIGINT, cancel_epoch BIGINT, state VARCHAR(30), expires_at TIMESTAMP)");
        db.execute("CREATE TABLE id2_subject(issuer VARCHAR(300),subject_id VARCHAR(200),PRIMARY KEY(issuer,subject_id))");
        db.execute("CREATE TABLE con5_feature_revision(task_id VARCHAR(36),revision_id VARCHAR(36),revision_number BIGINT,PRIMARY KEY(task_id,revision_id,revision_number))");
        db.execute("CREATE TABLE con5_preparation(task_id VARCHAR(36),preparation_id VARCHAR(36),PRIMARY KEY(task_id,preparation_id))");
        db.execute("CREATE TABLE con5_prepared_document(preparation_id VARCHAR(36),document_role VARCHAR(16),document_key VARCHAR(64),PRIMARY KEY(preparation_id,document_role,document_key))");
        db.execute("CREATE TABLE con3_worker_delegation(delegation_id VARCHAR(36) PRIMARY KEY,task_id VARCHAR(36),human_issuer VARCHAR(300),human_subject VARCHAR(200),service_subject VARCHAR(200),authorization_version BIGINT,status VARCHAR(20),expires_at TIMESTAMP)");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("db/dm8"))) { root = root.getParent(); if(root == null) throw new IllegalStateException("Repository migrations required"); }
        for (var file : List.of("V095__product_comparison_input.sql", "V098__product_comparison_worker_delegation.sql", "V099__product_comparison_candidate_lease.sql", "V100__product_comparison_matrix.sql", "V102__product_comparison_start_command.sql", "V103__comparison_start_fence_presence.sql")) {
            String sql = Files.readString(root.resolve("db/dm8").resolve(file)).replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
            for (var statement : sql.split(";")) if (!statement.isBlank()) db.execute(statement);
        }
        db.update("INSERT INTO con3_task VALUES('11111111-1111-1111-1111-111111111111',5,17,0,'IN_PROGRESS',?)", ts(UNTIL.plusSeconds(300)));
        db.update("INSERT INTO id2_subject VALUES('https://engineering.invalid','human')");
        db.update("INSERT INTO con5_feature_revision VALUES('11111111-1111-1111-1111-111111111111','feature',1)");
        db.update("INSERT INTO con5_preparation VALUES('11111111-1111-1111-1111-111111111111','prepared')");
        db.update("INSERT INTO con5_comparison_input VALUES('22222222-2222-2222-2222-222222222222','11111111-1111-1111-1111-111111111111','feature',1,'prepared',5,NULL,'{}',?,2,'https://engineering.invalid','human','input-key',?,?,?)", SHA, REQUEST, ts(NOW), ts(UNTIL.plusSeconds(100)));
        for (var candidate : List.of(CANDIDATE)) {
            db.update("INSERT INTO con5_prepared_document VALUES('prepared','CANDIDATE',?)", candidate);
            db.update("INSERT INTO con5_comparison_candidate VALUES('22222222-2222-2222-2222-222222222222','prepared','CANDIDATE',?,?,'QUEUED')", candidate, 0);
        }
        db.update("INSERT INTO con5_comparison_current VALUES('11111111-1111-1111-1111-111111111111','22222222-2222-2222-2222-222222222222')");
    }
    static Timestamp ts(Instant value){return Timestamp.from(value);}
    ProductComparisonStartCommands store(){return new ProductComparisonStartCommands(db,tx,Clock.fixed(NOW,ZoneOffset.UTC));}
    void delegation(String id){
        db.update("INSERT INTO con3_worker_delegation VALUES(?,?,'https://engineering.invalid','human','worker',17,'ACTIVE',?)",id,TASK,ts(UNTIL));
        db.update("INSERT INTO con5_comparison_delegation VALUES(?,?,?,?,?,?,'worker',?,?)",id,TASK,COMPARISON,CANDIDATE,SHA,REQUEST,ts(NOW),ts(UNTIL));
    }
    void attempt(String id,String delegation,String request){
        db.update("INSERT INTO con5_candidate_attempt VALUES(?,?,?,?,?,5,0,'worker',?,?,?, ?,1,'ACTIVE',NULL,?,?)",id,TASK,COMPARISON,CANDIDATE,SHA,delegation,request,REQUEST,SHA,ts(NOW),ts(UNTIL));
        db.update("INSERT INTO con5_candidate_lease VALUES(?,?,?,1,'ACTIVE',?)",COMPARISON,CANDIDATE,id,ts(UNTIL));
    }
    static String hash(String text) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    /** Seed storage closure ONLY, NOT a calculation or approved model receipt. */
    String seedClosure(String attempt,String version) throws Exception {
        String response="{\"fixture\":\"storage-only-no-model\"}";
        String coords="\"taskId\":\""+TASK+"\",\"comparisonId\":\""+COMPARISON+"\",\"candidateId\":\""+CANDIDATE+"\",\"inputSha\":\""+SHA+"\",\"attemptId\":\""+attempt+"\",\"token\":1,\"reportId\":\"report\"";
        String report="{"+coords+",\"configurationVersion\":\""+version+"\",\"workerSubject\":\"worker\",\"responseSha\":\""+hash(response)+"\",\"approvalSha\":\""+REQUEST+"\"}";
        String sha=hash(report), receipt="{"+coords+",\"reportSha\":\""+sha+"\",\"status\":\"COMMITTED\"}";
        db.update("UPDATE con5_candidate_attempt SET status='COMMITTED' WHERE attempt_id=?",attempt);
        db.update("UPDATE con5_candidate_lease SET status='COMMITTED' WHERE attempt_id=?",attempt);
        db.update("UPDATE con5_comparison_candidate SET status='COMMITTED'");
        db.update("INSERT INTO con5_matrix_invocation VALUES(?,?,?,?,'COMMITTED',?,?,?)",attempt,REQUEST,REQUEST,version,hash(response),response,ts(NOW));
        db.update("INSERT INTO con5_matrix_report VALUES('report',?,?,?,?,1,?,?,?,?,?)",TASK,COMPARISON,CANDIDATE,attempt,SHA,sha,report,ts(NOW),ts(UNTIL));
        db.update("INSERT INTO con5_matrix_receipt VALUES(?,'report',?,?,?)",attempt,sha,receipt,hash(receipt)); return sha;
    }
    static final class Tls implements AutoCloseable {
        final Path root=Files.createTempDirectory("comparison-start-mtls-ca-");
        final String password=UUID.randomUUID().toString();
        Tls() throws Exception {
            key("-genkeypair","-alias","ca","-dname","CN=Disposable Comparison Engineering CA","-ext","bc:c","-ext","ku=keyCertSign,cRLSign","-keystore","ca.p12");
            key("-exportcert","-alias","ca","-keystore","ca.p12","-file","ca.cer");
            key("-importcert","-noprompt","-alias","ca","-keystore","trust.p12","-file","ca.cer");
            var empty=KeyStore.getInstance("PKCS12");empty.load(null,null);try(var out=Files.newOutputStream(root.resolve("no-trust.p12"))){empty.store(out,password.toCharArray());}
            leaf("server","CN=localhost","serverAuth","san=dns:localhost,ip:127.0.0.1");
            leaf("worker","CN=Engineering Comparison Worker","clientAuth",null);
        }
        void leaf(String name,String dn,String usage,String san) throws Exception {
            key("-genkeypair","-alias",name,"-dname",dn,"-keystore",name+".p12");
            key("-certreq","-alias",name,"-keystore",name+".p12","-file",name+".csr");
            var args=new ArrayList<>(List.of("-gencert","-alias","ca","-keystore","ca.p12","-infile",name+".csr","-outfile",name+".cer","-ext","eku="+usage,"-ext","ku=digitalSignature,keyEncipherment"));
            if(san!=null){args.add("-ext");args.add(san);} key(args.toArray(String[]::new));
            key("-importcert","-noprompt","-alias","ca","-keystore",name+".p12","-file","ca.cer");
            key("-importcert","-noprompt","-alias",name,"-keystore",name+".p12","-file",name+".cer");
        }
        void key(String... args) throws Exception {
            var command=new ArrayList<String>();command.add(Path.of(System.getProperty("java.home"),"bin","keytool.exe").toString());
            command.addAll(List.of(args));command.addAll(List.of("-storetype","PKCS12","-storepass",password));
            if(List.of(args).contains("-genkeypair"))command.addAll(List.of("-keyalg","RSA","-keysize","2048","-validity","2"));
            if(List.of(args).contains("-gencert"))command.addAll(List.of("-validity","2"));
            var p=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();if(p.waitFor()!=0)throw new IllegalStateException("Disposable engineering keytool failed (secret output suppressed)");
        }
        SSLContext context(boolean clientKey,boolean trustCa) throws Exception {
            return context(clientKey?"worker":null,trustCa);
        }
        SSLContext context(String name,boolean trustCa) throws Exception {
            KeyManager[] keys=null;
            if(name!=null){var ks=load(root.resolve(name+".p12"));var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(ks,password.toCharArray());keys=km.getKeyManagers();}
            var ts=KeyStore.getInstance("PKCS12");ts.load(null,null);if(trustCa)ts=load(root.resolve("trust.p12"));
            var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ts);
            var ssl=SSLContext.getInstance("TLS");ssl.init(keys,tm.getTrustManagers(),null);return ssl;
        }
        KeyStore load(Path path) throws Exception {var ks=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(path)){ks.load(in,password.toCharArray());}return ks;}
        public void close() throws Exception {erase(root);}
    }
    static void erase(Path directory) throws Exception {
        // Only the exact freshly created temporary directory; never a workspace/computed parent.
        if(directory==null||!directory.toAbsolutePath().getParent().equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()))throw new IllegalStateException("Unexpected temporary root");
        try(var paths=Files.walk(directory)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
    }
}
