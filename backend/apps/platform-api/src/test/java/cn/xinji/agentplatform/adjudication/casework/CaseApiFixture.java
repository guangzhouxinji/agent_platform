package cn.xinji.agentplatform.adjudication.casework;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.*;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository.*;
import cn.xinji.agentplatform.security.product.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** A real embedded HTTPS server with persisted signing keys/sessions/current grants, never a mock Access. */
final class CaseApiFixture implements AutoCloseable {
    final CaseApiDatabases databases;
    final CaseApiTls tls;
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final CaseWorkCodec codec=new CaseWorkCodec(mapper);
    final JsonNode pack;
    final String caseId,issuer;
    String endpoint;
    final HttpClient member,maintenance;
    final Map<String,String> tokens=new HashMap<>();
    final List<Map<String,Object>> exchanges=new ArrayList<>();
    final Map<String,Object> configurationOverrides=new HashMap<>();
    ServletWebServerApplicationContext context;
    CaseApiFixture(boolean dm8)throws Exception{this(dm8,Path.of("data/adjudication/task-first-loop-input-v1/records.json"));}
    CaseApiFixture(boolean dm8,Path inputPack)throws Exception{
        databases=new CaseApiDatabases(dm8);tls=new CaseApiTls();
        issuer="https://engineering-issuer-"+UUID.randomUUID()+".invalid";
        pack=mapper.readTree(Files.readString(inputPack));caseId=pack.path("caseInput").path("id").asText();
        databases.independent.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) VALUES(?,0,'READY',NULL,?)",issuer,AdjudicationCaseWork.sha256(""));
        databases.independent.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,'SOURCES,GRANTS','ACTIVE',1)",
                "accountable-engineering-maintainer",pack.path("caseInput").path("organization").asText(),tls.certificateSha);
        for(var subject:List.of("case-author","independent-reviewer","ungranted-user"))databases.db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at,subject_kind) VALUES(?,?,?,'encoded-engineering-password','ACTIVE',1,?,'HUMAN')",
                issuer,subject,subject,Timestamp.from(Instant.now()));
        member=HttpClient.newBuilder().sslContext(tls.memberTls).connectTimeout(Duration.ofSeconds(20)).build();
        maintenance=HttpClient.newBuilder().sslContext(tls.maintenanceTls).connectTimeout(Duration.ofSeconds(20)).build();
        restart();context.getBean(ProductSigningKeys.class).rotate("accountable-engineering-maintainer");
        for(var subject:List.of("case-author","independent-reviewer","ungranted-user"))tokens.put(subject,token(subject));
        for(var action:List.of("CREATE","READ","WRITE","HUMAN_REVIEW"))grant("case-author",action);
        for(var action:List.of("READ","HUMAN_REVIEW"))grant("independent-reviewer",action);
        register(codec.request(pack.path("caseInput"),CaseInput.class).source());
        for(var material:pack.path("materials"))register(codec.request(material,Material.class).source());
        if(pack.hasNonNull("claim"))register(codec.request(pack.path("claim"),Claim.class).source());
    }
    void restart(){
        if(context!=null)context.close();
        byte[] master=new byte[32];Arrays.fill(master,(byte)93);
        var values=new HashMap<String,Object>();
        values.put("server.port",0);values.put("server.ssl.enabled",true);values.put("server.ssl.key-store",tls.store.toUri().toString());
        values.put("server.ssl.key-store-password",tls.password);values.put("server.ssl.key-store-type","PKCS12");values.put("server.ssl.key-alias","adjudication");
        values.put("server.ssl.trust-store",tls.trustStore.toUri().toString());values.put("server.ssl.trust-store-password",tls.password);values.put("server.ssl.trust-store-type","PKCS12");values.put("server.ssl.client-auth","want");
        values.put("spring.datasource.url",databases.primaryUrl);values.put("spring.datasource.username",databases.user);values.put("spring.datasource.password",databases.password);
        values.put("spring.datasource.driver-class-name",databases.primaryUrl.startsWith("jdbc:dm:")?"dm.jdbc.driver.DmDriver":"org.h2.Driver");
        values.put("product.identity.enabled",true);values.put("adjudication.v2.enabled",true);values.put("product.identity.issuer",issuer);
        values.put("product.identity.audience","adjudication-work-api");values.put("product.identity.master-key",Base64.getEncoder().encodeToString(master));
        values.put("product.identity.token-verification-window","PT1H");values.put("product.identity.activation-lifetime","PT30M");values.put("product.identity.minimum-password-length",16);
        values.put("product.identity.authority.jdbc-url",databases.authorityUrl);values.put("product.identity.authority.username",databases.user);
        values.put("product.identity.authority.password",databases.password);values.put("product.identity.authority.schema",databases.schema);
        values.put("spring.profiles.active","adjudication-product");values.put("spring.main.banner-mode","off");values.put("logging.level.root","WARN");
        values.putAll(configurationOverrides);
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(ProductAdjudicationApplication.class).run(values.entrySet().stream().map(e->"--"+e.getKey()+"="+e.getValue()).toArray(String[]::new));
        endpoint="https://127.0.0.1:"+context.getWebServer().getPort();
    }
    private String token(String subject)throws Exception{
        var keys=context.getBean(ProductSigningKeys.class);var key=(RSAKey)keys.get(new JWKSelector(new JWKMatcher.Builder().privateOnly(true).build()),null).getFirst();
        String sid=UUID.randomUUID().toString(),jti=UUID.randomUUID().toString();var now=Instant.now();var until=now.plus(Duration.ofHours(1));
        databases.db.update("INSERT INTO id2_session(sid,jti,issuer,subject_id,client_id,kid,issued_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,'ACTIVE')",
                sid,jti,issuer,subject,"adjudication-engineering-client",key.getKeyID(),Timestamp.from(now),Timestamp.from(until));
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),new JWTClaimsSet.Builder().issuer(issuer).subject(subject)
                .audience("adjudication-work-api").claim("sid",sid).jwtID(jti).issueTime(Date.from(now)).expirationTime(Date.from(until)).build());
        jwt.sign(new RSASSASigner(key));return jwt.serialize();
    }
    String grant(String subject,String action){
        var scope=new ProductGrants.Scope(issuer,subject,pack.path("caseInput").path("organization").asText(),"ADJUDICATION",caseId,
                pack.path("caseInput").path("dataScope").asText(),"ADJ_CASE",caseId,1,action,"CASE_AUXILIARY");
        var receipt=context.getBean(ProductGrantWatermark.class).change(()->context.getBean(ProductGrants.class).issue(scope,Instant.now().plus(Duration.ofHours(1)),0,
                "accountable-engineering-maintainer",UUID.randomUUID().toString(),AdjudicationCaseWork.sha256(codec.encode(scope))));return receipt.grantId();
    }
    void register(Source source)throws Exception{
        var scope=new Scope(caseId,pack.path("caseInput").path("organization").asText(),pack.path("caseInput").path("dataScope").asText());
        boolean model=pack.has("modelApproval") && !source.id().equals(pack.path("caseInput").path("source").path("id").asText());
        var use=new CaseSourceAuthority.Use(scope,source,model?Set.of("READ","SAVE","ANALYZE","MODEL"):Set.of("READ","SAVE","ANALYZE"),Instant.now().plus(Duration.ofHours(1)),
                model?pack.path("modelApproval").path("reference").asText():"user-20261002-engineering-input-only",
                model?pack.path("modelApproval").path("sha256").asText():AdjudicationCaseWork.sha256(Files.readString(Path.of("data/adjudication/task-first-loop-input-v1/manifest.json"))));
        send(maintenance,null,"POST","/v2/adjudication/source-uses",mapper.valueToTree(use),null,200);
    }
    JsonNode send(String actor,String method,String path,JsonNode body,int status)throws Exception{return send(member,tokens.get(actor),method,path,body,method.equals("GET")?null:UUID.randomUUID().toString(),status);}
    JsonNode command(String suffix,long version,JsonNode nested,String field)throws Exception{
        var body=mapper.createObjectNode().put("expectedVersion",version);if(field!=null)body.set(field,nested);
        return send("case-author","POST","/v2/adjudication/case-works/"+caseId+suffix,body,200);
    }
    JsonNode send(HttpClient client,String token,String method,String path,JsonNode body,String key,int expected)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(60)).header("Accept","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);if(key!=null)request.header("Idempotency-Key",key);
        if(body!=null)request.header("Content-Type","application/json");
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        JsonNode result=response.body().isBlank()?mapper.nullNode():mapper.readTree(response.body());
        exchanges.add(Map.of("method",method,"path",path,"status",response.statusCode(),"response",result));
        assertEquals(expected,response.statusCode(),()->method+" "+path+" "+response.body());return result;
    }
    JsonNode create()throws Exception{
        var body=mapper.createObjectNode().set("input",pack.path("caseInput"));((com.fasterxml.jackson.databind.node.ObjectNode)body).put("originalText",pack.path("caseOriginalText").asText());
        return send("case-author","POST","/v2/adjudication/case-works",body,200);
    }
    JsonNode current()throws Exception{return send("case-author","GET","/v2/adjudication/case-works/"+caseId,null,200);}
    @Override public void close()throws Exception{if(context!=null)context.close();databases.close();tls.close();}
}
