package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Mechanical original/span C, not a user-confirmed real professional target. */
class ProductTechnicalSchemeOriginalTest {
    private static final String TASK="11111111-1111-4111-8111-111111111111", MESSAGE="22222222-2222-4222-8222-222222222222";
    private ProductTechnicalSchemeOriginal original(String text) {
        return new ProductTechnicalSchemeOriginal(TASK,MESSAGE,text,
                ProductCapturedArchive.digest(text.getBytes(StandardCharsets.UTF_8)),65536);
    }
    @Test void preservesOriginalWhitespaceAndMapsUnicodeToExactUtf8Bytes() {
        String text="  电池😀\nA  "; var original=original(text);
        assertThat(original.text()).isEqualTo(text);
        var span=original.evidence(2,6);
        assertThat(span.byteStart()).isEqualTo(2); assertThat(span.byteEnd()).isEqualTo(12);
        assertThat(span.text()).isEqualTo("电池😀");
        assertThat(span.spanSha()).isEqualTo(ProductCapturedArchive.digest("电池😀".getBytes(StandardCharsets.UTF_8)));
        assertThat(span.documentVersion()).startsWith("USER_MESSAGE_SHA256:");
        assertThat(span.originalRef()).isEqualTo("TASK_MESSAGE:"+TASK+":"+MESSAGE);
        assertThat(original.matches(span)).isTrue();
        assertThatThrownBy(()->original.evidence(4,5)).hasMessage("TECHNICAL_ORIGINAL_SPAN_INVALID");
    }
    @Test void wrongShaInvalidUnicodeAndBudgetReject() {
        assertThatThrownBy(()->new ProductTechnicalSchemeOriginal(TASK,MESSAGE,"Valve", "a".repeat(64),65536))
                .hasMessage("TECHNICAL_ORIGINAL_SHA_MISMATCH");
        assertThatThrownBy(()->original("Valve \ud800")).hasMessage("TECHNICAL_ORIGINAL_UNICODE_INVALID");
        assertThatThrownBy(()->new ProductTechnicalSchemeOriginal(TASK,MESSAGE,"电池",
                ProductCapturedArchive.digest("电池".getBytes(StandardCharsets.UTF_8)),5))
                .hasMessage("TECHNICAL_ORIGINAL_BUDGET_EXCEEDED");
    }
    @Test void fabricatedTextAndNegativeSpanNeverVerify() {
        var original=original("Valve 😀"); var span=original.evidence(0,5);
        var falseText=new cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText.Evidence(
                span.documentVersion(),span.originalRef(),span.originalSha(),span.jsonPointer(),
                span.byteStart(),span.byteEnd(),span.spanSha(),"Invented");
        assertThat(original.matches(falseText)).isFalse();
        var negative=new cn.xinji.agentplatform.consultation.patentcomparison.OriginalClaimText.Evidence(
                span.documentVersion(),span.originalRef(),span.originalSha(),span.jsonPointer(),-1,5,span.spanSha(),span.text());
        assertThat(original.matches(negative)).isFalse();
    }
}
