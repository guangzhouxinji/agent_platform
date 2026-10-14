package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProductCompleteRerankCacheTest {
    private final PatentReranker.Model model=new PatentReranker.Model("mechanical-C","model","revision",
            "a".repeat(64),"b".repeat(64),512,"mechanical-C");
    @Test void exactFullInputsReuseActualScoreButDoNotOwnCandidateIdentity() {
        var cache=new ProductCompleteRerankCache(2);var calls=new AtomicInteger();
        var first=new PatentReranker.Pair("first","query","CLAIMS\nfull original and tail");
        assertThat(cache.score(model,first,()->{calls.incrementAndGet();return -2.5;})).isEqualTo(-2.5);
        assertThat(cache.score(model,new PatentReranker.Pair("other","query",first.documentText()),
                ()->{throw new AssertionError("Exact inference should be reused");})).isEqualTo(-2.5);
        assertThat(calls).hasValue(1);
    }
    @Test void changedTailQueryFieldLabelOrModelCannotReuseScore() {
        var cache=new ProductCompleteRerankCache(8);var calls=new AtomicInteger();
        cache.score(model,new PatentReranker.Pair("doc","ab","c"),calls::incrementAndGet);
        assertThat(cache.score(model,new PatentReranker.Pair("doc","a","bc"),calls::incrementAndGet)).isEqualTo(2);
        cache.score(model,new PatentReranker.Pair("doc","ab","c changed tail"),calls::incrementAndGet);
        cache.score(model,new PatentReranker.Pair("doc","ab","CLAIMS\nc"),calls::incrementAndGet);
        var other=new PatentReranker.Model(model.provider(),model.modelId(),"new-revision",
                model.modelSha256(),model.tokenizerSha256(),512,model.licenseId());
        cache.score(other,new PatentReranker.Pair("doc","ab","c"),calls::incrementAndGet);
        assertThat(calls).hasValue(5);
    }
    @Test void failuresAndNonfiniteScoresNeverBecomeReusableResults() {
        var cache=new ProductCompleteRerankCache(1);var pair=new PatentReranker.Pair("doc","query","original");
        assertThatThrownBy(()->cache.score(model,pair,()->{throw new IllegalStateException("actual inference failed");}))
                .hasMessage("actual inference failed");
        assertThatThrownBy(()->cache.score(model,pair,()->Double.NaN)).hasMessageContaining("not finite");
        assertThat(cache.score(model,pair,()->3)).isEqualTo(3);
    }
    @Test void leastRecentlyUsedEntriesAreEvictedWithoutPersistingInputs() {
        var cache=new ProductCompleteRerankCache(2);var calls=new AtomicInteger();
        var a=new PatentReranker.Pair("a","query","A");var b=new PatentReranker.Pair("b","query","B");
        cache.score(model,a,calls::incrementAndGet);cache.score(model,b,calls::incrementAndGet);
        cache.score(model,a,calls::incrementAndGet);
        cache.score(model,new PatentReranker.Pair("c","query","C"),calls::incrementAndGet);
        assertThat(cache.score(model,b,calls::incrementAndGet)).isEqualTo(4);assertThat(calls).hasValue(4);
    }
    @Test void invalidInputsAndUnboundedConfigurationAreRejected() {
        assertThatThrownBy(()->new ProductCompleteRerankCache(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ProductCompleteRerankCache(1025)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ProductCompleteRerankCache(1).score(model,
                new PatentReranker.Pair("doc"," ","content"),()->1)).isInstanceOf(IllegalArgumentException.class);
    }
}
