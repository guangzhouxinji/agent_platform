package cn.xinji.agentplatform.adjudication.casework;

import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

/** Real trusted TLS and client certificate, generated solely for isolated product-code tests. */
final class CaseApiTls implements AutoCloseable {
    final Path directory;
    final Path store;
    final Path trustStore;
    final String password;
    final String certificateSha;
    final SSLContext memberTls;
    final SSLContext maintenanceTls;
    CaseApiTls() throws Exception {
        directory=Files.createTempDirectory("adj-api-tls-");store=directory.resolve("transport.p12");trustStore=directory.resolve("trust.p12");password="T9"+UUID.randomUUID().toString().replace("-","")+"z";
        String tool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        var process=new ProcessBuilder(tool,"-genkeypair","-alias","adjudication","-keyalg","RSA","-keysize","2048","-storetype","PKCS12",
                "-keystore",store.toString(),"-storepass",password,"-keypass",password,"-dname","CN=localhost","-validity","2",
                "-ext","SAN=ip:127.0.0.1,dns:localhost","-ext","EKU=serverAuth,clientAuth").redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if(process.waitFor()!=0)throw new IllegalStateException("Isolated TLS generation failed: "+output);
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,password.toCharArray());}
        var certificate=(X509Certificate)keys.getCertificate("adjudication");
        certificateSha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        var anchors=KeyStore.getInstance("PKCS12");anchors.load(null,password.toCharArray());anchors.setCertificateEntry("transport-trust",certificate);
        try(var trustOutput=Files.newOutputStream(trustStore)){anchors.store(trustOutput,password.toCharArray());}
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(anchors);
        var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password.toCharArray());
        memberTls=SSLContext.getInstance("TLS");memberTls.init(null,trust.getTrustManagers(),null);
        maintenanceTls=SSLContext.getInstance("TLS");maintenanceTls.init(managers.getKeyManagers(),trust.getTrustManagers(),null);
    }
    @Override public void close()throws Exception{
        // Explicit known files only, not an unchecked recursive workspace deletion.
        Files.deleteIfExists(store);Files.deleteIfExists(trustStore);Files.deleteIfExists(directory);
    }
}
