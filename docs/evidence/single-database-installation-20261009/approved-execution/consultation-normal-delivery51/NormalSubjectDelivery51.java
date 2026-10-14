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
public final class NormalSubjectDelivery51 {
    static final String HOST="consultation.agent-platform.internal",BASE="https://"+HOST+":5446";
    static final String SERVER="de4f652f8f99db36e7b7049fab104de0c0918a0e68dcc2dbd26fad6451374f06";
    static final String MAINTAINER="e02bae001010137b736d5a667008bc7b8ad06120006188f81edbd7eb958ad4de";
    static final String HTML="4ac2c752f350f2c142d6740039d72b587d46990ad96696af795e8d07d8dd39a1";
    static Path out;static String clientAlias;
    static String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    static void window(){Instant n=Instant.now();if(n.isBefore(Instant.parse("2026-10-09T16:52:53Z"))||!n.isBefore(Instant.parse("2026-10-10T10:00:00Z")))throw new IllegalStateException("APPROVAL_WINDOW_REFUSED");}
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
    public static void main(String[] args) {
      try {
        if(args.length!=3)throw new IllegalArgumentException();window();
        if(Boolean.getBoolean("jdk.internal.httpclient.disableHostnameVerification"))throw new IllegalStateException();
        InetAddress[] ip=InetAddress.getAllByName(HOST);if(ip.length!=1||!"127.0.0.1".equals(ip[0].getHostAddress()))throw new IllegalStateException();
        char[] password=Objects.requireNonNull(System.getenv("CONSULTATION_ORIGINAL_STORE_PASSWORD")).toCharArray();
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
        if(!"ab658c0492f34775181a5a97cc46fd2ecf04bcf1ecbe08f073124fadd0eeb9f0".equals(hash(root.getEncoded())))throw new IllegalStateException();
        var h=http(context(keyManagers,root));var discovery=get(h,"/.well-known/openid-configuration");
        if(discovery.statusCode()!=200||!new String(discovery.body(),StandardCharsets.UTF_8).contains("\"issuer\":\""+BASE+"\""))throw new IllegalStateException();
        if(!args[2].matches("[a-z][a-z0-9-]{2,63}"))throw new IllegalArgumentException();
        window();var post=HttpRequest.newBuilder(URI.create(BASE+"/v2/identity/subjects")).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString("{\"loginName\":\""+args[2]+"\"}")).build();
        var response=h.send(post,HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200||response.body().length>4096)throw new IllegalStateException();
        // Private process stdout MUST be consumed into caller memory and immediately DPAPI sealed.
        // Never redirect it to an evidence log or invoke this tool unwrapped.
        System.out.write(response.body());System.out.flush();
      } catch(Exception e) {System.err.println("NORMAL_SUBJECT_REGISTRATION_UNKNOWN_OR_REFUSED_NO_BLIND_RETRY");System.exit(1);}
    }
}