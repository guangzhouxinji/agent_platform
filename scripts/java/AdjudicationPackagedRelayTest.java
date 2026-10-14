import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.jar.*;
/** Tests the delivery JAR, including lazy-loaded Pair; no product/DB/TLS access. */
public final class AdjudicationPackagedRelayTest {
 static void check(boolean b){if(!b)throw new AssertionError("packaged relay invariant");}
 public static void main(String[] args)throws Exception{
  Path jar=Path.of(args[0]).toRealPath();Set<String> expected=Set.of("AdjudicationLocalTlsPublisher.class","AdjudicationLocalTlsPublisher$Config.class","AdjudicationLocalTlsPublisher$Pair.class"),actual=new HashSet<>();
  try(JarFile j=new JarFile(jar.toFile())){j.stream().filter(e->e.getName().endsWith(".class")).forEach(e->actual.add(e.getName()));check(actual.equals(expected));check("AdjudicationLocalTlsPublisher".equals(j.getManifest().getMainAttributes().getValue("Main-Class")));}
  for(String c:expected){Class<?> t=Class.forName(c.substring(0,c.length()-6));check(Path.of(t.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar));}
  System.out.println("PASS exact-three-production-classes-and-jar-codesource");
  byte[] req=new byte[65536],resp=new byte[32768];new Random(42).nextBytes(req);new Random(43).nextBytes(resp);
  Set<AdjudicationLocalTlsPublisher.Pair> pairs=ConcurrentHashMap.newKeySet();Semaphore slots=new Semaphore(1);check(slots.tryAcquire());
  try(ServerSocket front=new ServerSocket(0,1,InetAddress.getLoopbackAddress());ServerSocket back=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket client=new Socket(InetAddress.getLoopbackAddress(),front.getLocalPort());Socket rc=front.accept();Socket rt=new Socket(InetAddress.getLoopbackAddress(),back.getLocalPort());Socket target=back.accept()){
   for(Socket s:List.of(client,rc,rt,target))s.setSoTimeout(3000);
   AdjudicationLocalTlsPublisher.Pair pair=new AdjudicationLocalTlsPublisher.Pair(rc,rt,pairs,slots);pairs.add(pair);ExecutorService pool=Executors.newFixedThreadPool(3);
   try{Future<?> a=pool.submit(()->pair.pump(rc,rt)),b=pool.submit(()->pair.pump(rt,rc));Future<byte[]> received=pool.submit(()->{byte[] got=target.getInputStream().readAllBytes();target.getOutputStream().write(resp);target.shutdownOutput();return got;});client.getOutputStream().write(req);client.shutdownOutput();check(Arrays.equals(resp,client.getInputStream().readAllBytes()));check(Arrays.equals(req,received.get(4,TimeUnit.SECONDS)));a.get(4,TimeUnit.SECONDS);b.get(4,TimeUnit.SECONDS);System.out.println("PASS packaged-Pair-byte-duplex-and-half-close");check(pairs.isEmpty()&&slots.availablePermits()==1&&rc.isClosed()&&rt.isClosed());pair.close();check(slots.availablePermits()==1);System.out.println("PASS packaged-Pair-cleanup-and-idempotent-slot-release");}finally{pool.shutdownNow();pair.close();}
  }
  System.out.println("RESULT passed=3 failed=0 skipped=0 scope=DELIVERY_JAR_ENGINEERING_ONLY");
 }
}
