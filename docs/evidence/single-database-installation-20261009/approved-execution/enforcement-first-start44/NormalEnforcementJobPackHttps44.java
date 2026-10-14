import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import javax.net.ssl.*;

/** Acceptance tool: original PKCS12 in memory, standard JSSE trust/hostname checks. No private key writes. */
public final class NormalEnforcementJobPackHttps44 {
    static final String HOST="enforcement.agent-platform.internal",BASE="https://"+HOST+":5447";
    static final String SERVER="3010d992d99283525931acdd4886b43548e1d274903321f9e45de00280f7b9dc";
    static final String MAINTAINER="74d07e92c1602dda7d6d8335d53538cbd8ac2f27c1c0973c9b1295b3f7c72ba3";
    static final String HTML="07c9248171e3b886bf9b0f35bc3ce8fddcab2fb78d591ca3bd2c6c9eb589ed15";
    static Path out;static String clientAlias;
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static void window(){Instant n=Instant.now();if(n.isBefore(Instant.parse("2026-10-08T19:00:00Z"))||!n.isBefore(Instant.parse("2026-10-10T10:00:00Z")))throw new IllegalStateException("APPROVAL_WINDOW_REFUSED");}
    static void save(String name,byte[] b)throws Exception{Files.write(out.resolve(name),b,StandardOpenOption.CREATE_NEW);}
    static void json(String name,String content)throws Exception{save(name,(content+"\n").getBytes(StandardCharsets.UTF_8));}
    static SSLContext context(KeyManager[] keys,X509Certificate root)throws Exception{
        KeyStore trust=KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("approved-project-root",root);
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(trust);
        X509ExtendedTrustManager delegate=(X509ExtendedTrustManager)factory.getTrustManagers()[0];
        X509ExtendedTrustManager pinned=new X509ExtendedTrustManager(){
            void pin(X509Certificate[] c)throws CertificateException{try{if(c.length==0||!SERVER.equals(hash(c[0].getEncoded())))throw new CertificateException("ORIGINAL_SERVER_CERT_REQUIRED");}catch(CertificateException e){throw e;}catch(Exception e){throw new CertificateException(e);}}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{delegate.checkServerTrusted(c,a);pin(c);}
            public void checkServerTrusted(X509Certificate[] c,String a,Socket s)throws CertificateException{delegate.checkServerTrusted(c,a,s);pin(c);}
            public void checkServerTrusted(X509Certificate[] c,String a,SSLEngine e)throws CertificateException{delegate.checkServerTrusted(c,a,e);pin(c);}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{delegate.checkClientTrusted(c,a);}
            public void checkClientTrusted(X509Certificate[] c,String a,Socket s)throws CertificateException{delegate.checkClientTrusted(c,a,s);}
            public void checkClientTrusted(X509Certificate[] c,String a,SSLEngine e)throws CertificateException{delegate.checkClientTrusted(c,a,e);}
            public X509Certificate[] getAcceptedIssuers(){return delegate.getAcceptedIssuers();}
        };
        SSLContext ctx=SSLContext.getInstance("TLSv1.2");ctx.init(keys,new TrustManager[]{pinned},null);return ctx;
    }
    static HttpClient http(SSLContext ctx){
        SSLParameters p=new SSLParameters();p.setProtocols(new String[]{"TLSv1.2"});p.setEndpointIdentificationAlgorithm("HTTPS");
        return HttpClient.newBuilder().sslContext(ctx).sslParameters(p).connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
    }
    static HttpResponse<byte[]> get(HttpClient h,String path)throws Exception{
        return h.send(HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static String quoted(String v){StringBuilder b=new StringBuilder().append((char)34);for(char c:v.toCharArray()){if(c==34||c==92)b.append((char)92);if(c==10){b.append((char)92).append('n');}else if(c==13){b.append((char)92).append('r');}else b.append(c);}return b.append((char)34).toString();}
    static void record(String name,Map<String,?> fields)throws Exception{StringJoiner j=new StringJoiner(",","{","}");for(var e:fields.entrySet()){Object v=e.getValue();j.add(quoted(e.getKey())+":"+(v instanceof Number||v instanceof Boolean?v.toString():quoted(String.valueOf(v))));}json(name,j.toString());}
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("EXACT_SOURCE_STORE_CA_BODY_PUBLIC_CERT_OUTPUT_REQUIRED");
        out=Path.of(args[4]);window();
        if(Boolean.getBoolean("jdk.internal.httpclient.disableHostnameVerification"))throw new IllegalStateException("NO_HOSTNAME_BYPASS");
        InetAddress[] ip=InetAddress.getAllByName(HOST);if(ip.length!=1||!"127.0.0.1".equals(ip[0].getHostAddress()))throw new IllegalStateException("EXACT_HOST_DNS_REQUIRED");
        char[] password=Objects.requireNonNull(System.getenv("ENFORCEMENT_ORIGINAL_STORE_PASSWORD")).toCharArray();
        KeyStore store=KeyStore.getInstance("PKCS12");KeyManager[] keyManagers;
        try{
            try(InputStream in=Files.newInputStream(Path.of(args[0]))){store.load(in,password);}
            var aliases=store.aliases();int matches=0;
            while(aliases.hasMoreElements()){String a=aliases.nextElement();if(store.isKeyEntry(a)&&MAINTAINER.equals(hash(store.getCertificate(a).getEncoded()))){clientAlias=a;matches++;((X509Certificate)store.getCertificate(a)).checkValidity();}}
            if(matches!=1)throw new IllegalStateException("ORIGINAL_MAINTAINER_KEY_REQUIRED");
            KeyManagerFactory k=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());k.init(store,password);
            X509ExtendedKeyManager delegate=(X509ExtendedKeyManager)k.getKeyManagers()[0];
            keyManagers=new KeyManager[]{new X509ExtendedKeyManager(){
                String approved(String a){return clientAlias.equals(a)?a:null;}
                public String chooseClientAlias(String[] t,Principal[] i,Socket s){return approved(delegate.chooseClientAlias(t,i,s));}
                public String chooseEngineClientAlias(String[] t,Principal[] i,SSLEngine e){return approved(delegate.chooseEngineClientAlias(t,i,e));}
                public String[] getClientAliases(String t,Principal[] i){String[] a=delegate.getClientAliases(t,i);return a!=null&&Arrays.asList(a).contains(clientAlias)?new String[]{clientAlias}:null;}
                public String[] getServerAliases(String t,Principal[] i){return null;}
                public String chooseServerAlias(String t,Principal[] i,Socket s){return null;}
                public String chooseEngineServerAlias(String t,Principal[] i,SSLEngine e){return null;}
                public X509Certificate[] getCertificateChain(String a){return clientAlias.equals(a)?delegate.getCertificateChain(a):null;}
                public PrivateKey getPrivateKey(String a){return clientAlias.equals(a)?delegate.getPrivateKey(a):null;}
            }};
        }finally{Arrays.fill(password,'\0');}
        CertificateFactory cf=CertificateFactory.getInstance("X.509");X509Certificate root;
        try(InputStream in=Files.newInputStream(Path.of(args[1]))){root=(X509Certificate)cf.generateCertificate(in);}
        if(!"caf63a4e91a610e551126e515462c82cfea768ae01acc1bbadcb46db7d49a3f5".equals(hash(root.getEncoded())))throw new IllegalStateException("APPROVED_CA_REQUIRED");
        SSLContext ctx=context(keyManagers,root);HttpClient h=http(ctx);HttpResponse<byte[]> page=get(h,"/app/enforcement/job-pack");
        save("java-actual-adjudication-page.html",page.body());String peer=hash(page.sslSession().orElseThrow().getPeerCertificates()[0].getEncoded());
        json("java-page-result.json","{\"status\":"+page.statusCode()+",\"sha256\":\""+hash(page.body())+"\",\"peerSha256\":\""+peer+"\",\"normalMaintainerCertificate\":true,\"tlsProtocol\":\""+page.sslSession().orElseThrow().getProtocol()+"\",\"certificateStoreWrites\":0}");
        if(page.statusCode()!=200||!HTML.equals(hash(page.body()))||!SERVER.equals(peer))throw new IllegalStateException("FIXED_HTML_REQUIRED");
        var discovery=get(h,"/.well-known/openid-configuration");save("java-actual-openid-configuration.json",discovery.body());
        if(discovery.statusCode()!=200||!new String(discovery.body(),StandardCharsets.UTF_8).contains("\"issuer\":\""+BASE+"\""))throw new IllegalStateException("EXACT_ISSUER_REQUIRED");
        X509Certificate wrongRoot;try(InputStream in=Files.newInputStream(Path.of(args[3]))){wrongRoot=(X509Certificate)cf.generateCertificate(in);}
        boolean wrongCa=false,wrongName=false;
        try{get(http(context(null,wrongRoot)),"/app/enforcement/job-pack");}catch(SSLHandshakeException e){wrongCa=true;}
        try(Socket tcp=new Socket("127.0.0.1",5447);SSLSocket ssl=(SSLSocket)ctx.getSocketFactory().createSocket(tcp,"wrong-name.invalid",5447,true)){
            SSLParameters p=ssl.getSSLParameters();p.setProtocols(new String[]{"TLSv1.2"});p.setEndpointIdentificationAlgorithm("HTTPS");ssl.setSSLParameters(p);ssl.setSoTimeout(10000);
            try{ssl.startHandshake();}catch(SSLHandshakeException e){wrongName=true;}
        }
        if(!wrongCa||!wrongName)throw new IllegalStateException("TLS_NEGATIVES_REQUIRED");
        byte[] body=Files.readAllBytes(Path.of(args[2]));if(!"068d310c26db53820636d5bea14be49346879e9c77e32e0b44b86e8b94103a35".equals(hash(body)))throw new IllegalStateException("ORIGINAL_CLIENT_BODY_REQUIRED");
        HttpClient publicH=http(context(null,root));
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest("adjudication-entry-negative-20261010-40".getBytes(StandardCharsets.UTF_8)));
        String ownAuth="/oauth2/authorize?response_type=code&client_id=enforcement-job-pack-product&redirect_uri="+URLEncoder.encode(BASE+"/app/enforcement/job-pack",StandardCharsets.UTF_8)+"&scope=openid&state=entry40&code_challenge="+challenge+"&code_challenge_method=S256";
        var unregistered=get(publicH,ownAuth);save("oauth-unregistered-client-response.bin",unregistered.body());record("oauth-unregistered-client-result.json",Map.of("status",unregistered.statusCode(),"bodySha256",hash(unregistered.body()),"location",unregistered.headers().firstValue("location").orElse("")));
        if(unregistered.statusCode()!=400)throw new IllegalStateException("UNREGISTERED_CLIENT_REFUSAL_REQUIRED_BEFORE_REGISTER");
        String foreignAuth="/oauth2/authorize?response_type=code&client_id=consultation-workspace-product&redirect_uri="+URLEncoder.encode("https://consultation.agent-platform.internal:5446/app/consultation",StandardCharsets.UTF_8)+"&scope=openid&state=cross40&code_challenge="+challenge+"&code_challenge_method=S256";
        var foreign=get(publicH,foreignAuth);save("oauth-foreign-client-response.bin",foreign.body());record("oauth-foreign-client-result.json",Map.of("status",foreign.statusCode(),"bodySha256",hash(foreign.body()),"issuer",BASE,"clientId","consultation-workspace-product"));if(foreign.statusCode()!=400)throw new IllegalStateException("FOREIGN_DOMAIN_CLIENT_REFUSAL_REQUIRED");
        String adjudicationAuth="/oauth2/authorize?response_type=code&client_id=adjudication-case-workspace&redirect_uri="+URLEncoder.encode("https://adjudication.agent-platform.internal:5448/app/adjudication",StandardCharsets.UTF_8)+"&scope=openid&state=foreignAdj44&code_challenge="+challenge+"&code_challenge_method=S256";
        var foreignAdj=get(publicH,adjudicationAuth);record("oauth-foreign-adjudication-result.json",Map.of("status",foreignAdj.statusCode(),"bodySha256",hash(foreignAdj.body())));if(foreignAdj.statusCode()!=400)throw new IllegalStateException("FOREIGN_ADJ_CLIENT_REFUSAL_REQUIRED");
        for(String path:List.of("/app/enforcement/document-templates","/app/enforcement/document-rules","/app/enforcement/legal-references","/v2/enforcement/document-templates","/v2/enforcement/document-rules","/v2/enforcement/legal-references","/v2/enforcement/legal-basis")){var gated=get(publicH,path);record("gated-"+path.replace('/','_')+".json",Map.of("path",path,"status",gated.statusCode()));if(gated.statusCode()!=404)throw new IllegalStateException("UNADMITTED_PAGE_API_MUST_BE_GATED");}
        String[][] assets={{"/app/assets/EnforcementDocumentRuleV2Page-DoZoqG-x.js","fc1549721d15fd14c5da0df02284628c3a4f7159b5990e454d63f49b62d774e0"},{"/app/assets/EnforcementDocumentRuleV2Page-DoZoqG-x.js.map","7cf4b81e4f7a67ac5b86a2be8aa9bd2f0fbc3c1e6047f3fbe42ff83d95e9c8ed"},{"/app/assets/EnforcementDocumentTemplateV2Page-C4lvIed5.js","186fe69d772dfeb928c9bb25281d2625a6895542ac5e8c16d4894ab06e08216a"},{"/app/assets/EnforcementDocumentTemplateV2Page-C4lvIed5.js.map","b113946c75a7ae2b1d5e98b7ab1a93fa10d37f18eedb335ccd9e7d2266b4fcf6"},{"/app/assets/EnforcementJobPackV2Page-BJXXcstt.js","7e2f2218f9980f0b8a75b9cb454576d2e5f484cf4abdcca6b5993e764cb8b09d"},{"/app/assets/EnforcementJobPackV2Page-BJXXcstt.js.map","d2319fdc9f1fee6e80e338341805232b471947a75f86ba43d91e50a7f47c43a3"},{"/app/assets/EnforcementJobPackV2Page-DNyKydSx.css","2603b48c7f264f76b62cb2c5b6b4b4db7ae1c3447e9f1ece22c79eac29b10a17"},{"/app/assets/EnforcementLegalReferenceV2Page-Bhs39ln9.js","b83c6b7ba254731d314c850471db93aa62e50d7689e8564c9532cdd56db8420e"},{"/app/assets/EnforcementLegalReferenceV2Page-Bhs39ln9.js.map","7d9d2e3379f8d0f07701ef6eca9772f9f2f5a1e9f363e03ba8c0def6975f6253"},{"/app/assets/EnforcementNextV2Page-CSR-wyzS.js","a9fe7a5cc41c2e66e3389f2c80211d8805d36242e680823bc998a44576a2f88f"},{"/app/assets/EnforcementNextV2Page-CSR-wyzS.js.map","eb0b21cec80e342d1ccd80e2b08fbbef390793567db96fe2f807642729320542"},{"/app/assets/EnforcementNextV2Page-rQQlOtRq.css","a55b09037b9e897e57e9af581370011dba16dcc138d4b0b5609e94ee91cd00c9"},{"/app/assets/enforcement-C-_eZJcR.js","56ce6afb196f550ae09c97435be39fb46007b81535c31722c99a8f7fef5028eb"},{"/app/assets/enforcement-C-_eZJcR.js.map","5f566554fda57191280d61b6dcf9ae615e593361fde87677f017824de429c354"},{"/app/assets/enforcement-DHYUiFSi.css","6cf2b5bd4e3a4573edd439623e33fa316124877d7e64189bda64a0c1fe6f14d8"},{"/app/assets/enforcementNextV2Api-BU_8rX0_.js","392b4169d9390dc905fa2e5b47a5fb4d72120d541030d00a363f5bfd4a86dbf5"},{"/app/assets/enforcementNextV2Api-BU_8rX0_.js.map","150ed67fe65d210e2f928c2ae84ba10416154a3f7197352ad3edfc128e23f7d3"},{"/app/assets/input-number-B_prqefD.js","3d82c452f899bab618d38d08c8145a5f7ce0e31c3fafd8d0891589186696eef7"},{"/app/assets/input-number-B_prqefD.js.map","2486bfc4dee8114514d15b3f7b6f808f0b00837c89d2d052cdea0eb1d289c516"},{"/app/assets/progress-ljjBO3AP.js","ce72bfe744c522a4d192a973edf640d0c287dc54a4a2d2ff1674c90673b22264"},{"/app/assets/progress-ljjBO3AP.js.map","74bf0e8d50e3cd11ef4c18e25cd5e342d354c2e153da4e40cc4ca320020e6542"},{"/app/assets/refresh-cw-DqMto5Ac.js","97f1beee9407a0f058ff6ac2191d90c76505bca1358256255fbb56cf0e8054ab"},{"/app/assets/refresh-cw-DqMto5Ac.js.map","fd5227e29d478661de1a2cee2ec52cda26f1028e7da209dcb9b3af8323e187b7"},{"/app/assets/rolldown-runtime-CbXtAM7H.js","bbf7c5086ae414dc3f33777e3eebf16ea8631325b8bb4690d25091c03567f31a"},{"/app/enforcement.html","07c9248171e3b886bf9b0f35bc3ce8fddcab2fb78d591ca3bd2c6c9eb589ed15"}};
        for(int i=0;i<assets.length;i++){var resource=get(publicH,assets[i][0]);save("static-resource-"+i+".bin",resource.body());record("static-resource-"+i+"-result.json",Map.of("status",resource.statusCode(),"path",assets[i][0],"sha256",hash(resource.body())));if(resource.statusCode()!=200||!assets[i][1].equals(hash(resource.body())))throw new IllegalStateException("EXACT_STATIC_ASSET_REQUIRED");}
        window();
        var post=HttpRequest.newBuilder(URI.create(BASE+"/v2/identity/clients")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json")
            .header("Idempotency-Key","single-final-enforcement-job-pack-20261009-23-01").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var noMaint=publicH.send(post,HttpResponse.BodyHandlers.ofByteArray());save("no-maintainer-registration-response.bin",noMaint.body());record("no-maintainer-registration-result.json",Map.of("status",noMaint.statusCode(),"bodySha256",hash(noMaint.body()),"requestId","single-final-enforcement-job-pack-20261009-23-01"));if(noMaint.statusCode()!=403)throw new IllegalStateException("NO_MAINTENANCE_CERT_REFUSAL_REQUIRED");
        var registered=h.send(post,HttpResponse.BodyHandlers.ofByteArray());save("java-client-registration-response.json",registered.body());
        json("java-client-registration-result.json","{\"status\":"+registered.statusCode()+",\"requestId\":\"single-final-enforcement-job-pack-20261009-23-01\",\"bodySha256\":\""+hash(body)+"\",\"maintainerCertificateSha256\":\""+MAINTAINER+"\",\"businessGrantCreated\":false,\"ordinaryMemberCreated\":false}");
        if(registered.statusCode()!=200)throw new IllegalStateException("REGISTRATION_RESPONSE_PRESERVED_NO_BLIND_RETRY");
        String receipt=new String(registered.body(),StandardCharsets.UTF_8);
        if(!receipt.contains("\"clientId\":\"enforcement-job-pack-product\"")||!receipt.contains("\"version\":1")||!receipt.contains("\"status\":\"ACTIVE\""))throw new IllegalStateException("NORMAL_CLIENT_RECEIPT_REQUIRED");
        record("entry-domain-boundary-result.json",Map.of("unregisteredOwnClientStatus",400,"foreignDomainClientStatus",400,"noMaintainerStatus",403,"jsAndCssExact",true,"registeredReceiptVersion",1,"modelCalls",0));
        window();var denied=http(context(null,root)).send(post,HttpResponse.BodyHandlers.ofByteArray());
        if(denied.statusCode()!=403)throw new IllegalStateException("NO_CERT_MAINTENANCE_REFUSAL_REQUIRED");
        var after=get(publicH,ownAuth);String location=after.headers().firstValue("location").orElse("");record("registered-pkce-result.json",Map.of("status",after.statusCode(),"location",location));if(after.statusCode()!=302||!location.equals(BASE+"/login"))throw new IllegalStateException("NORMAL_REGISTERED_PKCE_LOGIN_REQUIRED");
        var login=get(publicH,"/login");save("normal-login-page.html",login.body());record("normal-login-page-result.json",Map.of("status",login.statusCode(),"bodySha256",hash(login.body())));if(login.statusCode()!=200)throw new IllegalStateException("NORMAL_LOGIN_PAGE_REQUIRED");
        json("java-normal-https-result.json","{\"format\":\"ACTUAL_ORIGINAL_MAINTAINER_MTLS_AND_CLIENT/v1\",\"capturedAt\":\""+Instant.now()+"\",\"pageStatus\":200,\"pageSha256\":\""+HTML+"\",\"peerSha256\":\""+peer+"\",\"issuer\":\""+BASE+"\",\"mTlsVerifiedByMaintenanceApi\":true,\"clientRegistered\":true,\"noCertificateStatus\":"+denied.statusCode()+",\"wrongCaRefused\":true,\"wrongHostnameRefused\":true,\"privateKeyCopied\":false,\"certificateStoreWrites\":0,\"ordinaryMemberLoginVerified\":false,\"taskSourceGrantVerified\":false,\"firstNoModelInputVerified\":false,\"revocationRestartVerified\":false,\"modelCalls\":0,\"professionalAccepted\":false}");
        System.out.println("NORMAL_ORIGINAL_MTLS_PAGE_ISSUER_AND_CLIENT_VERIFIED_NO_ORDINARY_BUSINESS_ACCEPTANCE");
    }
}



