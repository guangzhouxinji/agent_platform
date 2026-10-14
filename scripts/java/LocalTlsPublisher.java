import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.Security;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Formal, fixed-target TCP byte transport. No TLS, HTTP, authentication or domain data. */
public final class LocalTlsPublisher {
    static final String TARGET_ID = "9b0037591eef0fae1ec5da5c10adbd78d1d49d741741476389542fb70ad83c1f";
    static final Set<String> KEYS = Set.of("version", "targetId", "targetDns", "targetIp", "internalSubnet",
        "publishSubnet", "listenPort", "targetPort", "maxConnections", "connectMillis", "idleMillis");
    record Config(String targetId, String targetDns, InetAddress targetIp, String internalSubnet,
                  String publishSubnet, int maxConnections, int connectMillis, int idleMillis) { }
    static Config readConfig(Path file) throws IOException {
        Map<String,String> m = new HashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty()) continue;
            String[] v = line.split("=", -1);
            if (v.length != 2 || !KEYS.contains(v[0]) || m.putIfAbsent(v[0], v[1]) != null)
                throw new IOException("PUBLISH_CONFIG_REFUSED");
        }
        if (!m.keySet().equals(KEYS) || !"LOCAL_TLS_PUBLISH/v1".equals(m.get("version")) ||
            !TARGET_ID.equals(m.get("targetId")) || !TARGET_ID.substring(0,12).equals(m.get("targetDns")) ||
            !"172.25.0.0/16".equals(m.get("internalSubnet")) || !"172.17.0.0/16".equals(m.get("publishSubnet")) ||
            !"8443".equals(m.get("listenPort")) || !"8443".equals(m.get("targetPort")) ||
            !"16".equals(m.get("maxConnections")) || !"5000".equals(m.get("connectMillis")) ||
            !"30000".equals(m.get("idleMillis"))) throw new IOException("PUBLISH_CONFIG_REFUSED");
        InetAddress ip = ipv4(m.get("targetIp"));
        if (!inSubnet(ip, m.get("internalSubnet"))) throw new IOException("PUBLISH_TARGET_SUBNET_REFUSED");
        return new Config(TARGET_ID, TARGET_ID.substring(0,12), ip, m.get("internalSubnet"),
            m.get("publishSubnet"), 16, 5000, 30000);
    }
    static InetAddress ipv4(String value) throws IOException {
        if (value == null || !value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) throw new IOException("PUBLISH_IPV4_REFUSED");
        String[] v=value.split("\\."); byte[] b=new byte[4];
        for(int i=0;i<4;i++){ int n=Integer.parseInt(v[i]); if(n>255 || !Integer.toString(n).equals(v[i]))
            throw new IOException("PUBLISH_IPV4_REFUSED"); b[i]=(byte)n; }
        return InetAddress.getByAddress(b);
    }
    static boolean inSubnet(InetAddress ip, String subnet) throws IOException {
        String[] parts=subnet.split("/"); if(parts.length!=2 || !(ip instanceof Inet4Address))return false;
        byte[] a=ip.getAddress(), b=ipv4(parts[0]).getAddress(); int mask=Integer.parseInt(parts[1]);
        if(mask<1 || mask>32)throw new IOException("PUBLISH_SUBNET_REFUSED");
        for(int i=0;i<4;i++){int bits=Math.min(8,Math.max(0,mask-i*8));int m=(0xff << (8-bits))&0xff;
            if((a[i]&m)!=(b[i]&m))return false;}
        return true;
    }
    static InetAddress validateAddresses(Config c, InetAddress[] addresses) throws IOException {
        if(addresses.length!=1 || !(addresses[0] instanceof Inet4Address) || !addresses[0].equals(c.targetIp()))
            throw new IOException("PUBLISH_TARGET_DNS_DRIFT"); return addresses[0];
    }
    static InetAddress resolveTarget(Config c) throws IOException {
        return validateAddresses(c, InetAddress.getAllByName(c.targetDns()));
    }
    static void assertForwarding(Path ipv4, Path ipv6) throws IOException {
        if(!"0".equals(Files.readString(ipv4).trim()) || !"0".equals(Files.readString(ipv6).trim()))
            throw new IOException("PUBLISH_FORWARDING_REFUSED");
    }
    static InetAddress publisherInterface(Config c) throws IOException {
        List<InetAddress> publish=new ArrayList<>(), internal=new ArrayList<>();
        var interfaces=NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()){
            var n=interfaces.nextElement(); if(n.isLoopback() || !n.isUp())continue;
            var addresses=n.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress a=addresses.nextElement();if(!(a instanceof Inet4Address))continue;
                if(inSubnet(a,c.publishSubnet()))publish.add(a);
                else if(inSubnet(a,c.internalSubnet()))internal.add(a);
                else throw new IOException("PUBLISH_INTERFACE_DRIFT");
            }
        }
        if(publish.size()!=1 || internal.size()!=1 || publish.get(0).equals(internal.get(0)))
            throw new IOException("PUBLISH_INTERFACE_DRIFT"); return publish.get(0);
    }
    static void copy(Socket source, Socket destination) throws IOException {
        byte[] buffer=new byte[8192]; InputStream in=source.getInputStream();OutputStream out=destination.getOutputStream();
        int count;while((count=in.read(buffer))!=-1){out.write(buffer,0,count);} destination.shutdownOutput();
    }
    static final class Pair implements AutoCloseable {
        final Socket client, target;final Set<Pair> pairs;final Semaphore slots;
        final AtomicInteger copies=new AtomicInteger(2);boolean closed;
        Pair(Socket c, Socket t, Set<Pair> p, Semaphore s){client=c;target=t;pairs=p;slots=s;}
        void pump(Socket from,Socket to){try{copy(from,to);}catch(IOException e){close();}
            finally{if(copies.decrementAndGet()==0)close();}}
        public synchronized void close(){if(closed)return;closed=true;try{client.close();}catch(IOException ignored){}
            try{target.close();}catch(IOException ignored){}pairs.remove(this);slots.release();}
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IOException("PUBLISH_CONFIG_PATH_REQUIRED");
        Config c=readConfig(Path.of(args[0]));
        Security.setProperty("networkaddress.cache.ttl","0");Security.setProperty("networkaddress.cache.negative.ttl","0");
        Path v4=Path.of("/proc/sys/net/ipv4/ip_forward"),v6=Path.of("/proc/sys/net/ipv6/conf/all/forwarding");
        assertForwarding(v4,v6); InetAddress listen=publisherInterface(c);resolveTarget(c);
        Set<Pair> pairs=ConcurrentHashMap.newKeySet();Semaphore slots=new Semaphore(c.maxConnections());
        ExecutorService workers=Executors.newFixedThreadPool(c.maxConnections()*2);
        try(ServerSocket server=new ServerSocket()){
            server.setReuseAddress(false);server.bind(new InetSocketAddress(listen,8443),16);server.setSoTimeout(1000);
            System.out.println("LOCAL_TLS_PUBLISH_READY target="+c.targetId()+" listen="+listen.getHostAddress()+":8443");
            while(true){
                assertForwarding(v4,v6);if(!publisherInterface(c).equals(listen))throw new IOException("PUBLISH_INTERFACE_DRIFT");
                resolveTarget(c); Socket client;
                try{client=server.accept();}catch(SocketTimeoutException e){continue;}
                if(!slots.tryAcquire()){client.close();continue;}
                Socket target=new Socket();
                try{
                    client.setSoTimeout(c.idleMillis());target.setSoTimeout(c.idleMillis());
                    target.connect(new InetSocketAddress(resolveTarget(c),8443),c.connectMillis());
                    Pair pair=new Pair(client,target,pairs,slots);pairs.add(pair);
                    workers.execute(()->pair.pump(client,target));workers.execute(()->pair.pump(target,client));
                }catch(IOException e){client.close();target.close();slots.release();
                    System.err.println("PUBLISH_CONNECTION_REFUSED");}
            }
        }finally{for(Pair p:pairs)p.close();workers.shutdownNow();}
    }
}
