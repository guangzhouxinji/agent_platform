import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Pure/loopback engineering tests. No product identity, Docker, data source or TLS acceptance. */
public final class EnforcementLocalTlsPublisherTest {
    static int passed;
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void test(String name, Checked body) throws Exception {body.run();passed++;System.out.println("PASS "+name);}
    interface Checked{void run()throws Exception;}
    static void refuse(Checked body)throws Exception{try{body.run();throw new AssertionError("Must refuse");}catch(IOException expected){}}
    static String props(){return "version=LOCAL_TLS_PUBLISH/v1\ntargetId="+EnforcementLocalTlsPublisher.TARGET_ID+
        "\ntargetDns=e0e5ddf6b3ca\ntargetIp=172.25.0.4\ninternalSubnet=172.25.0.0/16\npublishSubnet=172.17.0.0/16\nlistenPort=8443\ntargetPort=8443\nmaxConnections=16\nconnectMillis=5000\nidleMillis=30000\n";}
    static EnforcementLocalTlsPublisher.Config config(Path d,String s)throws Exception{Path p=d.resolve("config");Files.writeString(p,s);return EnforcementLocalTlsPublisher.readConfig(p);}
    public static void main(String[] args)throws Exception{
        Path d=Path.of(args[0]);Files.createDirectories(d);
        test("strict-fixed-config",()->{var c=config(d,props());check(c.targetId().equals(EnforcementLocalTlsPublisher.TARGET_ID));});
        test("unknown-and-duplicate-config",()->{refuse(()->config(d,props()+"other=bad\n"));refuse(()->config(d,props()+"targetPort=443\n"));});
        test("missing-and-target-switch",()->{refuse(()->config(d,props().replace("maxConnections=16\n","")));refuse(()->config(d,props().replace("targetDns=e0e5ddf6b3ca","targetDns=other")));});
        test("port-and-namespace-drift",()->{refuse(()->config(d,props().replace("listenPort=8443","listenPort=443")));refuse(()->config(d,props().replace("publishSubnet=172.17.0.0/16","publishSubnet=0.0.0.0/0")));});
        test("not-arbitrary-address",()->{refuse(()->config(d,props().replace("targetIp=172.25.0.4","targetIp=127.0.0.1")));refuse(()->EnforcementLocalTlsPublisher.ipv4("example.com"));refuse(()->EnforcementLocalTlsPublisher.ipv4("172.25.0.004"));refuse(()->EnforcementLocalTlsPublisher.ipv4("172.25.0.256"));});
        test("dns-only-one-exact-ip",()->{var c=config(d,props());check(EnforcementLocalTlsPublisher.validateAddresses(c,new InetAddress[]{EnforcementLocalTlsPublisher.ipv4("172.25.0.4")}).equals(c.targetIp()));refuse(()->EnforcementLocalTlsPublisher.validateAddresses(c,new InetAddress[]{}));refuse(()->EnforcementLocalTlsPublisher.validateAddresses(c,new InetAddress[]{c.targetIp(),c.targetIp()}));refuse(()->EnforcementLocalTlsPublisher.validateAddresses(c,new InetAddress[]{EnforcementLocalTlsPublisher.ipv4("172.25.0.5")}));});
        test("forwarding-always-off",()->{Path v4=d.resolve("v4"),v6=d.resolve("v6");Files.writeString(v4,"0\n");Files.writeString(v6,"0\n");EnforcementLocalTlsPublisher.assertForwarding(v4,v6);Files.writeString(v4,"1\n");refuse(()->EnforcementLocalTlsPublisher.assertForwarding(v4,v6));Files.writeString(v4,"0\n");Files.writeString(v6,"1\n");refuse(()->EnforcementLocalTlsPublisher.assertForwarding(v4,v6));});
        test("subnets-do-not-overlap",()->{check(EnforcementLocalTlsPublisher.inSubnet(EnforcementLocalTlsPublisher.ipv4("172.17.0.8"),"172.17.0.0/16"));check(!EnforcementLocalTlsPublisher.inSubnet(EnforcementLocalTlsPublisher.ipv4("172.25.0.8"),"172.17.0.0/16"));});
        test("byte-preserving-full-duplex-and-half-close",()->{
            byte[] request=new byte[65536],response=new byte[32768];new Random(33).nextBytes(request);new Random(34).nextBytes(response);
            try(ServerSocket front=new ServerSocket(0,1,InetAddress.getLoopbackAddress());ServerSocket back=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
                Socket client=new Socket(InetAddress.getLoopbackAddress(),front.getLocalPort());Socket relayClient=front.accept();
                Socket relayTarget=new Socket(InetAddress.getLoopbackAddress(),back.getLocalPort());Socket target=back.accept()){
                for(Socket s:List.of(client,relayClient,relayTarget,target))s.setSoTimeout(3000);
                ExecutorService pool=Executors.newFixedThreadPool(3);
                try{
                    Future<?> a=pool.submit(()->{try{EnforcementLocalTlsPublisher.copy(relayClient,relayTarget);}catch(IOException e){throw new RuntimeException(e);}});
                    Future<?> b=pool.submit(()->{try{EnforcementLocalTlsPublisher.copy(relayTarget,relayClient);}catch(IOException e){throw new RuntimeException(e);}});
                    Future<byte[]> received=pool.submit(()->{byte[] got=target.getInputStream().readAllBytes();target.getOutputStream().write(response);target.shutdownOutput();return got;});
                    client.getOutputStream().write(request);client.shutdownOutput();check(Arrays.equals(response,client.getInputStream().readAllBytes()));
                    check(Arrays.equals(request,received.get(4,TimeUnit.SECONDS)));a.get(4,TimeUnit.SECONDS);b.get(4,TimeUnit.SECONDS);
                }finally{pool.shutdownNow();}
            }
        });
        test("peer-failure-and-timeout-no-fake-result",()->{
            try(ServerSocket s=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket c=new Socket(InetAddress.getLoopbackAddress(),s.getLocalPort());Socket p=s.accept()){
                c.setSoTimeout(50);try{c.getInputStream().read();throw new AssertionError();}catch(SocketTimeoutException expected){}p.close();check(c.getInputStream().read()==-1);
            }
        });
        System.out.println("RESULT passed="+passed+" failed=0 skipped=0 scope=ENGINEERING_ONLY");
    }
}


