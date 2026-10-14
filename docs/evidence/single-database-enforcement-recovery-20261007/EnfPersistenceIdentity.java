package cn.xinji.agentplatform.security.product;
import java.time.*;import java.sql.Timestamp;import java.util.*;import java.security.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.security.oauth2.jwt.*;import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
/** Approved engineering maintenance only, uses current product grants, keys and decoder. */
public final class EnfPersistenceIdentity {
 public static final String ISSUER="https://enf-persistent-engineering.invalid/20261007",ACTOR="ENFPERS_FUNCTIONAL_SUBJECT",ORG="ORG-ENF-PROJECT",TEMPLATE="ENFPERS_PROJECT_TEMPLATE_20261007",AUDIENCE="enf-persistent-engineering",SID="ENFPERS_ENGINEERING_SESSION_01",JTI="ENFPERS_ENGINEERING_JTI_01";
 public static void bootstrap(JdbcTemplate db,JdbcTemplate auth,TransactionTemplate tx)throws Exception{
  String passwordHash=new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
  for(String s:List.of(ACTOR,"ENFPERS_ENGINEERING_MAINTAINER"))db.update("INSERT INTO id2_subject(issuer,subject_id,login_name,password_hash,status,credential_version,created_at) VALUES(?,?,?,?,'ACTIVE',1,?)",ISSUER,s,s,passwordHash,Timestamp.from(Instant.now()));
  String cert=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("EXPLICIT_ENGINEERING_ONLY_NOT_REAL_CERTIFICATE".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  auth.update("INSERT INTO id2_maintainer(singleton_key,subject_ref,organization,certificate_sha,allowed_roles,status,version) VALUES(1,?,?,?,'ENGINEERING_ONLY','ACTIVE',1)","ENFPERS_ENGINEERING_MAINTAINER",ORG,cert);
  auth.update("INSERT INTO id2_authority_watermark(issuer,generation,state,intent_id,history_sha) VALUES(?,0,'READY',NULL,?)",ISSUER,ProductGrantHistoryBatch.digest(db,ISSUER,false,false));
  var keys=keys(db,tx);String kid=keys.rotate("ENFPERS_ENGINEERING_MAINTAINER");
  var clients=new ProductClients(db,new ProductRegisteredClients(new org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository(db),db),tx,Clock.systemUTC());
  clients.register(new ProductClients.Input("enfpers-engineering-client","Engineering only template persistence","https://127.0.0.1:5174/app/enforcement/document-templates"),"enfpers-client-01","ENFPERS_ENGINEERING_MAINTAINER");
  db.update("INSERT INTO id2_session(sid,jti,issuer,subject_id,client_id,kid,issued_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,'ACTIVE')",SID,JTI,ISSUER,ACTOR,"enfpers-engineering-client",kid,Timestamp.from(Instant.now().minusSeconds(1)),Timestamp.from(Instant.now().plusSeconds(10800)));
  var watermark=new ProductGrantWatermark(db,auth,ISSUER);var grants=new ProductGrants(db,tx,Clock.systemUTC());
  for(String action:List.of("CREATE","READ","PREVIEW","PUBLISH","DISABLE","DOWNLOAD"))watermark.change(()->grants.issue(new ProductGrants.Scope(ISSUER,ACTOR,ORG,"ENFORCEMENT","ENF-PERSISTENT-ENGINEERING","FUNCTIONAL_VALIDATION","DOCUMENT_TEMPLATE",TEMPLATE,1,action,"DOCUMENT_TEMPLATE"),Instant.now().plusSeconds(10800),0,"ENFPERS_ENGINEERING_MAINTAINER","enfpers-grant-"+action,"f".repeat(64)));
  watermark.assertCurrent();
 }
 public static ProductSigningKeys keys(JdbcTemplate db,TransactionTemplate tx){return new ProductSigningKeys(db,tx,ISSUER,System.getenv("ENFP_WRAPPING_KEY"),Duration.ofHours(3),Clock.systemUTC());}
 public static Jwt verifiedJwt(JdbcTemplate db,JdbcTemplate auth,TransactionTemplate tx){var keys=keys(db,tx);var watermark=new ProductGrantWatermark(db,auth,ISSUER);var encoder=new NimbusJwtEncoder(keys);String kid=db.queryForObject("SELECT active_kid FROM id2_key_control WHERE issuer=?",String.class,ISSUER);Instant now=Instant.now();String raw=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build(),JwtClaimsSet.builder().issuer(ISSUER).subject(ACTOR).audience(List.of(AUDIENCE)).id(JTI).issuedAt(now.minusSeconds(1)).expiresAt(now.plusSeconds(3600)).claim("sid",SID).build())).getTokenValue();
  var decoder=new ProductIssuerConfiguration(new org.springframework.core.env.StandardEnvironment()).jwtDecoder(keys,db,watermark,ISSUER,AUDIENCE);Jwt jwt=decoder.decode(raw);
  // A genuinely changed signature must not become a verified identity.
  String[] parts=raw.split("\\.");byte[] sig=Base64.getUrlDecoder().decode(parts[2]);sig[0]^=1;boolean denied=false;try{decoder.decode(parts[0]+"."+parts[1]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sig));}catch(JwtException expected){denied=true;}if(!denied)throw new IllegalStateException("Product decoder accepted corrupted signature");return jwt;
 }
}

