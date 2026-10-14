package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import cn.xinji.agentplatform.consultation.patenttask.source.PatentReranker;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

class OnnxPatentRerankerTest {
    @Test
    @EnabledIfSystemProperty(named = "patent.reranker.model", matches = ".+")
    void completePairsKeepShortScoreAndProcessTailWithoutTruncatingQuery() {
        var model = new PatentReranker.Model("ONNX_RUNTIME", "cross-encoder/ms-marco-MiniLM-L-6-v2",
                "233902d25c440f23af6f7d6e94d2946bac0bee0a",
                "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9",
                "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",512,"Apache-2.0");
        try (var provider = new OnnxPatentReranker(Path.of(System.getProperty("patent.reranker.model")),
                Path.of(System.getProperty("patent.reranker.tokenizer")),model)) {
            var shortPairs = List.of(new PatentReranker.Pair("short","lithium battery",
                    "lithium battery energy storage apparatus"));
            double shortScore = provider.score(shortPairs).getFirst().value();
            assertThat(provider.scoreComplete(shortPairs).getFirst().value()).isCloseTo(shortScore,within(0.00001));
            String prefix = "optical coating inspection device. ".repeat(160);
            var tailPair = List.of(new PatentReranker.Pair("tail","lithium battery",prefix
                    + "lithium battery energy storage apparatus. ".repeat(60)));
            double legacy = provider.score(tailPair).getFirst().value();
            double complete = provider.scoreComplete(tailPair).getFirst().value();
            assertThat(complete).isGreaterThan(legacy);
            assertThat(provider.scoreComplete(tailPair).getFirst().value()).isEqualTo(complete);
            assertThatThrownBy(() -> provider.scoreComplete(List.of(new PatentReranker.Pair("long",
                    "battery ".repeat(600),"energy storage")))).isInstanceOf(IllegalStateException.class);
            System.out.printf("COMPLETE_RERANK short=%.12f legacyTail=%.12f completeTail=%.12f%n",
                    shortScore,legacy,complete);
            provider.close();
            assertThatThrownBy(()->provider.scoreComplete(tailPair)).hasMessage("Patent reranker is closed");
            assertThatThrownBy(()->provider.score(shortPairs)).hasMessage("Patent reranker is closed");
        }
    }
    @Test
    @EnabledIfSystemProperty(named = "patent.reranker.model", matches = ".+")
    void pinnedCrossEncoderScoresQueryDocumentPairs() {
        var model = new PatentReranker.Model("ONNX_RUNTIME",
                "cross-encoder/ms-marco-MiniLM-L-6-v2",
                "233902d25c440f23af6f7d6e94d2946bac0bee0a",
                "c80a8b34256ea453093d612e3ac48d3d965a0c0a48c7906709af8b8e28461bf9",
                "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66",
                512, "Apache-2.0");
        try (var provider = new OnnxPatentReranker(
                Path.of(System.getProperty("patent.reranker.model")),
                Path.of(System.getProperty("patent.reranker.tokenizer")), model)) {
            var scores = provider.score(List.of(
                    new PatentReranker.Pair("relevant", "lithium battery",
                            "lithium battery energy storage apparatus"),
                    new PatentReranker.Pair("unrelated", "lithium battery",
                            "optical coating inspection device")));
            assertThat(scores).hasSize(2);
            assertThat(scores.get(0).value()).isGreaterThan(scores.get(1).value());
        }
    }
}
