package cn.xinji.agentplatform.security.product;

import java.util.*;
import java.util.concurrent.*;

/** Request-local complete byte verification; bounded lanes, no source or authority cache. */
final class ProductSourceInventoryReads {
    record Reference(String name,String path,String sha,long size) { }
    private static final Semaphore APPLICATION_READS=new Semaphore(16);
    private ProductSourceInventoryReads() { }
    static Map<String,byte[]> read(ProductSourceEvidenceFiles files,List<Reference> references) {
        if(references.isEmpty()) return Map.of();
        int lanes=Math.min(4,references.size());
        byte[][] bytes=new byte[references.size()][];
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> jobs=new ArrayList<>();
            for(int lane=0;lane<lanes;lane++) {
                final int first=lane;
                jobs.add(executor.submit(()->{
                    for(int index=first;index<references.size();index+=lanes) {
                        var reference=references.get(index);
                        APPLICATION_READS.acquire();
                        try {
                            byte[] original=files.verified(reference.path(),reference.sha());
                            if(original.length!=reference.size()) throw new IllegalStateException("Native artifact length differs");
                            bytes[index]=original;
                        } finally { APPLICATION_READS.release(); }
                    }
                    return null;
                }));
            }
            for(var job:jobs) job.get();
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Native inventory read interrupted",interrupted);
        } catch(ExecutionException failed) {
            throw new IllegalStateException("Native inventory bytes unavailable",failed.getCause());
        }
        Map<String,byte[]> result=new LinkedHashMap<>();
        for(int index=0;index<references.size();index++) {
            if(bytes[index]==null || result.put(references.get(index).name(),bytes[index])!=null)
                throw new IllegalStateException("Native inventory incomplete or duplicate");
        }
        return result;
    }
}
