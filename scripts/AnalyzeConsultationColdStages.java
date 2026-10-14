import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import jdk.jfr.consumer.*;

/** Offline, allowlisted JFR diagnostics: no payload, environment, credentials or DB access. */
public final class AnalyzeConsultationColdStages {
    record Range(Instant from, Instant to) { }
    static final class Stat {
        long count, bytes;
        double totalMs, maxMs;
        void add(double ms, long weight) { count++; totalMs+=ms; maxMs=Math.max(maxMs,ms); bytes+=weight; }
        Map<String,Object> view() { return Map.of("count",count,"totalMs",totalMs,"maxMs",maxMs,"sampledAllocationBytes",bytes); }
    }
    static final class Window {
        final String name;
        final Instant from,to;
        final Map<String,Stat> stats=new TreeMap<>();
        final Map<String,Long> methods=new TreeMap<>(), cpu=new TreeMap<>();
        final List<Range> compilation=new ArrayList<>(), pauses=new ArrayList<>();
        long requestSamples, totalSamples;
        Window(String name, Instant from, Instant to) { this.name=name;this.from=from;this.to=to; }
        void add(String key,double ms,long bytes) { stats.computeIfAbsent(key,k->new Stat()).add(ms,bytes); }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("Existing JFR, cold and warm benchmark paths required");
        var mapper=new ObjectMapper();
        List<Window> windows=new ArrayList<>();
        for(int i=1;i<3;i++) {
            var b=mapper.readTree(Files.readAllBytes(Path.of(args[i])));
            if(b.path("runs").asInt()!=100 || b.path("concurrency").asInt()!=10)
                throw new IllegalArgumentException("Original 100/10 window required; do not change benchmark scope");
            Instant to=OffsetDateTime.parse(b.path("generatedAt").asText()).toInstant();
            windows.add(new Window(i==1?"cold":"warm",to.minusNanos((long)(b.path("elapsedMs").asDouble()*1e6)),to));
        }
        long allEvents=0;
        Set<String> allowed=Set.of("jdk.Compilation","jdk.GarbageCollection","jdk.GCPhasePause",
                "jdk.ExecutionSample","jdk.NativeMethodSample","jdk.ObjectAllocationSample",
                "jdk.ThreadPark","jdk.SocketRead");
        try(var recording=new RecordingFile(Path.of(args[0]))) {
            while(recording.hasMoreEvents()) {
                var event=recording.readEvent();allEvents++;
                String type=event.getEventType().getName();
                if(!allowed.contains(type)) continue;
                for(var w:windows) {
                    Instant start=event.getStartTime(),end=event.getEndTime();
                    if(end.isBefore(w.from)||start.isAfter(w.to)) continue;
                    Instant left=start.isBefore(w.from)?w.from:start, right=end.isAfter(w.to)?w.to:end;
                    double ms=Math.max(0,Duration.between(left,right).toNanos()/1e6);
                    var trace=event.getStackTrace();
                    List<String> frames=trace==null?List.of():trace.getFrames().stream()
                            .map(f->f.getMethod().getType().getName()+"."+f.getMethod().getName()).toList();
                    String business=frames.stream().filter(f->f.startsWith("cn.xinji.agentplatform."))
                            .findFirst().orElse("NO_BUSINESS_FRAME");
                    RecordedThread thread=event.hasField("sampledThread")?event.getThread("sampledThread"):
                            event.hasField("eventThread")?event.getThread("eventThread"):null;
                    boolean request=thread!=null&&thread.getJavaName()!=null&&thread.getJavaName().contains("5443-exec");
                    if(type.equals("jdk.Compilation")) {
                        w.add(type,ms,0);w.compilation.add(new Range(left,right));
                        RecordedMethod method=event.getValue("method");
                        if(method!=null) w.methods.merge(method.getType().getName()+"."+method.getName(),1L,Long::sum);
                    } else if(type.equals("jdk.GCPhasePause")) {
                        w.add(type,ms,0);w.pauses.add(new Range(left,right));
                    } else if(type.equals("jdk.GarbageCollection")) w.add(type,ms,0);
                    else if(type.endsWith("MethodSample")||type.endsWith("ExecutionSample")) {
                        w.totalSamples++;if(request)w.requestSamples++;
                        if(request||!business.equals("NO_BUSINESS_FRAME")) {
                            String key=type+" / "+(request?"HTTP":"OTHER_BUSINESS")+" / "+business
                                    +" / "+(frames.isEmpty()?"NO_FRAME":frames.getFirst());
                            if(!frames.isEmpty()&&frames.getFirst().contains("getHostByAddr"))
                                key+=" / "+String.join(" -> ",frames.stream().limit(12).toList());
                            w.cpu.merge(key,1L,Long::sum);w.add(type,0,0);
                        }
                    } else if(type.equals("jdk.ObjectAllocationSample")) {
                        if(request||!business.equals("NO_BUSINESS_FRAME"))
                            w.add("ALLOCATION / "+business,0,event.getLong("weight"));
                    } else if(request||!business.equals("NO_BUSINESS_FRAME")) {
                        String key=type+" / "+business;
                        if(type.equals("jdk.SocketRead")) key+=" / port="+event.getInt("port");
                        w.add(key,ms,0);
                    }
                }
            }
        }
        List<Object> results=new ArrayList<>();
        for(var w:windows) {
            Map<String,Object> stats=new TreeMap<>();w.stats.forEach((k,v)->stats.put(k,v.view()));
            results.add(Map.of("round",w.name,"from",w.from.toString(),"to",w.to.toString(),"stats",stats,
                    "compilationWallUnionMs",union(w.compilation),"gcPauseWallUnionMs",union(w.pauses),
                    "allExecutionSamples",w.totalSamples,"httpExecutionSamples",w.requestSamples,
                    "compiledMethods",top(w.methods,30),"cpuTopFrames",top(w.cpu,40)));
        }
        System.out.println(mapper.writeValueAsString(Map.of("schema","OFFLINE_COLD_STAGE_DIAGNOSTIC/v1",
                "eventsRead",allEvents,"windows",results,"databaseWrites",0,"modelCalls",0,
                "requestAuthorizationNotBypassed",true,"httpSloAcceptance",false,
                "limitations",List.of("Historical recording; not latest v662 causal proof",
                        "Compilation union is overlap, not request CPU causality; parallel totals are not wall time",
                        "Execution and allocation samples are weighted observations, not exhaustive CPU or bytes"))));
    }
    static List<Object> top(Map<String,Long> values,int limit) {
        return values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed())
                .limit(limit).map(v->(Object)Map.of("method",v.getKey(),"count",v.getValue())).toList();
    }
    static double union(List<Range> input) {
        if(input.isEmpty())return 0;
        var ranges=input.stream().sorted(Comparator.comparing(Range::from)).toList();
        Instant from=ranges.getFirst().from(),to=ranges.getFirst().to();long nanos=0;
        for(var r:ranges) {
            if(r.from().isAfter(to)){nanos+=Duration.between(from,to).toNanos();from=r.from();to=r.to();}
            else if(r.to().isAfter(to))to=r.to();
        }
        return (nanos+Duration.between(from,to).toNanos())/1e6;
    }
}
