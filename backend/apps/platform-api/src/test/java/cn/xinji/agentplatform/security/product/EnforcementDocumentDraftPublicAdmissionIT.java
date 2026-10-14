package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real product admission of retained public bytes. Controlled technical actors, not business acceptance. */
class EnforcementDocumentDraftPublicAdmissionIT {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    JdbcTemplate db; ProductGrants grants; ProductGrantWatermark watermark; HttpClient http;
    String issuer,org,actor,verifier,token,verifyToken,run; Path assets,output;
    final List<Map<String,Object>> commands=new ArrayList<>();
    void initialize()throws Exception {
        assertEquals("FUNCTIONAL_VALIDATION",required("ENF_DRAFT_IDENTITY_NATURE"));
        run=required("ENF_DRAFT_PUBLIC_RUN"); assets=Path.of(required("ENF_DRAFT_PUBLIC_ASSETS"));
        output=Path.of(required("ENF_DRAFT_PUBLIC_OUTPUT"));
        issuer=required("ENF_ACCEPTANCE_ISSUER"); assertEquals("https://127.0.0.1:5444",issuer);
        org=required("ENF_ACCEPTANCE_ORGANIZATION");actor=required("ENF_ACCEPTANCE_SUBJECT");
        verifier=required("ENF_DRAFT_VERIFIER_SUBJECT");assertNotEquals(actor,verifier);
        token=required("ENF_DRAFT_ACCESS_TOKEN");verifyToken=required("ENF_DRAFT_VERIFIER_TOKEN");
        var data=new DriverManagerDataSource(required("ENF_ACCEPTANCE_BUSINESS_URL"),required("ENF_ACCEPTANCE_BUSINESS_USER"),required("ENF_ACCEPTANCE_BUSINESS_PASSWORD"));
        var authority=new DriverManagerDataSource(required("ENF_ACCEPTANCE_AUTHORITY_URL"),required("ENF_ACCEPTANCE_AUTHORITY_USER"),required("ENF_ACCEPTANCE_AUTHORITY_PASSWORD")) {
            @Override public Connection getConnection()throws SQLException {var c=super.getConnection();c.setSchema("ENFACC_AUTH");return c;}
        };
        assertTrue(data.getUrl().startsWith("jdbc:dm://")); assertNotEquals(data.getUrl(),authority.getUrl());
        db=new JdbcTemplate(data);grants=new ProductGrants(db,new TransactionTemplate(new DataSourceTransactionManager(data)),Clock.systemUTC());
        watermark=new ProductGrantWatermark(db,new JdbcTemplate(authority),issuer);
        for(String subject:List.of(actor,verifier))assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM id2_subject WHERE issuer=? AND subject_id=? AND status='ACTIVE'",Integer.class,issuer,subject));
        var trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(var in=Files.newInputStream(Path.of(required("ENF_DRAFT_ROOT_CERT")))){trust.setCertificateEntry("local-product-root",CertificateFactory.getInstance("X.509").generateCertificate(in));}
        var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);
        http=HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(15)).build();
    }
    @Test void admitPublicOriginalAndMechanicalFieldsThroughHttpsProductCommands()throws Exception {
        initialize();
        byte[] original=Files.readAllBytes(assets.resolve("official-original.html"));
        byte[] derived=Files.readAllBytes(assets.resolve("derived-values.json"));
        var preparation=json.readTree(Files.readAllBytes(assets.resolve("public-input-preparation.json")));
        assertEquals(preparation.at("/source/rawSha256").asText(),sha(original));
        assertEquals(preparation.at("/derived/sha256").asText(),sha(derived));
        assertTrue(preparation.at("/collection/allowed").asBoolean());assertEquals(200,preparation.at("/source/originalHttpStatus").asInt());
        String source="ENF14-CNIPA-PUBLIC-"+run,rule="ENF14-CNIPA-DISCOVERY-"+run;
        String expires=Instant.now().plus(Duration.ofHours(24)).toString();
        String rights="PUBLIC_ACCESS_PROJECT_COLLECTION_V1:"+sha(original);
        var uses=List.of("SOURCE_MANAGEMENT","RULE_MANAGEMENT","SAVE","DISPLAY","QUERY","PREVIEW","ANALYZE");
        grant(actor,"ENFORCEMENT_SOURCE",source,1,"CONFIGURE","SOURCE_MANAGEMENT");
        grant(actor,"ENFORCEMENT_SOURCE",source,1,"SYNC","SAVE");
        command(token,"/sources",Map.of("id",source,"organization",org,"rightsRef",rights,"epoch",1,"expiresAt",expires,"uses",uses,"credentialRef","PUBLIC_HTTPS_NO_AUTH"));
        String positiveText=preparation.at("/source/title").asText();
        assertTrue(new String(original,java.nio.charset.StandardCharsets.UTF_8).contains(positiveText));
        String positive=command(token,"/observations",Map.of("sourceId",source,"recordId","CNIPA_GUIDANCE_CASE_1_INTERPRETATION:title","sourceVersion",sha(original),"text",positiveText,"materialMention",preparation.at("/source/originalUrl").asText())).path("resultId").asText();
        // Exact official header text is a negative literal example, never a fabricated case or finding.
        String negativeText="国家知识产权局";assertTrue(new String(original,java.nio.charset.StandardCharsets.UTF_8).contains(negativeText));
        String negative=command(token,"/observations",Map.of("sourceId",source,"recordId","CNIPA_GUIDANCE_CASE_1_INTERPRETATION:official-heading","sourceVersion",sha(original),"text",negativeText,"materialMention",preparation.at("/source/originalUrl").asText())).path("resultId").asText();
        assertFalse(positive.isBlank());assertFalse(negative.isBlank());
        grant(actor,"RULE",rule,1,"EDIT_RULE","RULE_MANAGEMENT");grant(actor,"RULE",rule,1,"PUBLISH_RULE","RULE_MANAGEMENT");
        for(String observation:List.of(positive,negative))grant(actor,"OBSERVATION",observation,1,"READ","DISPLAY");
        command(token,"/discovery-rules",Map.of("id",rule,"sourceId",source,"literal","上海章元信息技术有限公司","basisRef",preparation.at("/source/originalUrl").asText()+"#title"));
        assertEquals("MATCH",get(token,"/discovery-rules/"+rule+"/trial/"+positive).path("outcome").asText());
        assertEquals("NO_MATCH",get(token,"/discovery-rules/"+rule+"/trial/"+negative).path("outcome").asText());
        command(token,"/discovery-rules/"+rule+"/publish",Map.of("revision",1,"positiveObservationId",positive,"negativeObservationId",negative));
        grant(actor,"OBSERVATION",positive,1,"CREATE_CLUE","QUERY");
        String clue=command(token,"/discoveries",Map.of("observationId",positive,"ruleId",rule)).path("resultId").asText();assertFalse(clue.isBlank());
        grant(actor,"CLUE",clue,1,"REGISTER_MATERIAL","SAVE");
        var materials=new ArrayList<Map<String,Object>>();
        for(var item:Map.of("OFFICIAL_ORIGINAL",original,"MECHANICAL_DERIVATION",derived).entrySet()) {
            String id=command(token,"/materials",Map.of("clueId",clue,"bytes",Base64.getEncoder().encodeToString(item.getValue()),"rightsRef",rights,"expiresAt",expires,"uses",uses,"collector",actor,"collectedAt",preparation.at("/source/capturedAt").asText())).path("resultId").asText();assertFalse(id.isBlank());
            grant(actor,"MATERIAL",id,1,"COLLECT_ATTEST","SAVE");
            String locator=preparation.at("/source/originalUrl").asText()+ (item.getKey().equals("OFFICIAL_ORIGINAL")?"#retained-original":"#mechanical-title-and-party-derivation");
            command(token,"/materials/"+id+"/collection",Map.of("expectedVersion",1,"sourceRef",locator,"reason","FUNCTIONAL_VALIDATION: retained official bytes / mechanical extraction; no new case facts; capture evidence "+sha(Files.readAllBytes(assets.resolve("capture-evidence.json")))));
            grant(verifier,"MATERIAL",id,2,"VERIFY","SAVE");
            command(verifyToken,"/materials/"+id+"/verification",Map.of("expectedVersion",2,"reason","FUNCTIONAL_VALIDATION: independent technical original SHA / provenance check; NOT_BUSINESS_ACCEPTANCE"));
            grant(actor,"MATERIAL",id,3,"READ","DISPLAY");grant(actor,"MATERIAL",id,3,"PREVIEW","PREVIEW");
            var saved=get(token,"/materials/"+id);assertEquals("VERIFIED",saved.path("state").asText());assertEquals(3,saved.path("version").asLong());assertEquals(sha(item.getValue()),saved.path("sha256").asText());
            var response=http.send(request(token,"/materials/"+id+"/preview").GET().build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());assertArrayEquals(item.getValue(),response.body());
            var actions=get(token,"/materials/"+id+"/actions");assertEquals(3,actions.size());assertEquals(actor,actions.get(1).path("actor").asText());assertEquals(verifier,actions.get(2).path("actor").asText());
            materials.add(Map.of("kind",item.getKey(),"material",saved,"actions",actions,"originalSha256",sha(response.body()),"previewHttpStatus",200));
        }
        Files.createDirectories(output);
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("public-admission.json").toFile(),Map.of("run",run,"nature","FUNCTIONAL_VALIDATION_NOT_BUSINESS_ACCEPTANCE","source",source,"sourceExpiresAt",expires,"rightsMeaning","PROJECT_COLLECTION_ONLY_NOT_LEGAL_RIGHTS","clue",clue,"materials",materials,"commands",commands,"publicSource",preparation.path("source")));
    }
    void grant(String subject,String type,String id,long revision,String action,String purpose) {
        var epochs=db.query("SELECT authorization_epoch,current_grant_id FROM id2_current_epoch WHERE issuer=? AND subject_id=? AND organization=? AND domain_code='ENFORCEMENT' AND resource_type=? AND resource_id=? AND action_code=? AND purpose=?",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2)},issuer,subject,org,type,id,action,purpose);
        long epoch=epochs.isEmpty()?0:(Long)epochs.getFirst()[0];
        if(!epochs.isEmpty()&&epochs.getFirst()[1]!=null) {
            String old=(String)epochs.getFirst()[1];Long version=db.queryForObject("SELECT grant_version FROM id2_grant WHERE grant_id=?",Long.class,old);
            epoch=watermark.change(()->grants.revoke(old,version,"codex-public-input-technical-preparation",UUID.randomUUID().toString(),"b".repeat(64))).epoch();
        }
        long expected=epoch;
        watermark.change(()->grants.issue(new ProductGrants.Scope(issuer,subject,org,"ENFORCEMENT",run,"FUNCTIONAL_VALIDATION",type,id,revision,action,purpose),Instant.now().plusSeconds(7200),expected,"codex-public-input-technical-preparation",UUID.randomUUID().toString(),"c".repeat(64)));
    }
    HttpRequest.Builder request(String bearer,String path){return HttpRequest.newBuilder(URI.create(issuer+"/v2/enforcement"+path)).timeout(Duration.ofSeconds(30)).header("Authorization","Bearer "+bearer);}
    JsonNode command(String bearer,String path,Object body)throws Exception {
        var response=http.send(request(bearer,path).header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString()).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        commands.add(Map.of("path",path,"at",Instant.now().toString(),"httpStatus",response.statusCode()));
        assertEquals(200,response.statusCode(),"Product command failed: "+path+" HTTP "+response.statusCode());return json.readTree(response.body());
    }
    JsonNode get(String bearer,String path)throws Exception {var response=http.send(request(bearer,path).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),path);return json.readTree(response.body());}
    static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String required(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required; no skip");return value;}
}
