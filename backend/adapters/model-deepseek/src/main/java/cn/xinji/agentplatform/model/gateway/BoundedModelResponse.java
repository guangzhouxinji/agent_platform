package cn.xinji.agentplatform.model.gateway;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Bounds the entire streamed response before buffering it, not after allocating arbitrary upstream bytes. */
final class BoundedModelResponse implements HttpResponse.BodySubscriber<byte[]> {
    private final int limit;
    private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result=new CompletableFuture<>();
    private Flow.Subscription subscription;
    BoundedModelResponse(int limit) { this.limit=limit; }
    public CompletionStage<byte[]> getBody() { return result; }
    public void onSubscribe(Flow.Subscription subscription) {
        if (this.subscription!=null) { subscription.cancel(); return; }
        this.subscription=subscription; subscription.request(1);
    }
    public void onNext(List<ByteBuffer> chunks) {
        try {
            for (ByteBuffer chunk:chunks) {
                if (chunk.remaining()>limit-bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new cn.xinji.agentplatform.runtime.ModelGateway.Failure("OUTPUT_SIZE_LIMIT")); return;
                }
                byte[] raw=new byte[chunk.remaining()];chunk.get(raw);bytes.writeBytes(raw);
            }
            subscription.request(1);
        } catch (RuntimeException error) { subscription.cancel();result.completeExceptionally(error); }
    }
    public void onError(Throwable error) { result.completeExceptionally(error); }
    public void onComplete() { result.complete(bytes.toByteArray()); }
}
