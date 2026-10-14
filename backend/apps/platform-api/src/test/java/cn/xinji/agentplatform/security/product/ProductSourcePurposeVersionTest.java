package cn.xinji.agentplatform.security.product;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Mechanical identity comparison; a purpose application is not source admission evidence. */
class ProductSourcePurposeVersionTest {
    @Test void modelPurposeCanBeRequestedButOtherUsesDoNotBecomeApproved() {
        assertThat(ProductSourceAdmission.allowedUse("MODEL")).isTrue();
        for(String use:new String[]{"DOWNLOAD","SHARE","UNKNOWN","",null})
            assertThat(ProductSourceAdmission.allowedUse(use)).isFalse();
    }
    @Test void purposeApplicationIsNotCapturedContentButFieldsAndHashesRemainImmutable() throws Exception {
        var mapper=new ObjectMapper();
        String original="{\"sourceId\":\"source\",\"sourceVersion\":\"capture-sha\",\"manifestSha\":\"sha\","
                +"\"requestedFields\":[\"TITLE\"],\"requestedUses\":{\"TITLE\":[\"QUERY\"]}}";
        var changed=mapper.readTree(original).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)changed.path("requestedUses"))
                .putArray("TITLE").add("QUERY").add("MODEL");
        assertThat(ProductSourceCaptureIdentity.normalized(mapper,original))
                .isEqualTo(ProductSourceCaptureIdentity.normalized(mapper,mapper.writeValueAsString(changed)));
        for(String field:new String[]{"sourceId","sourceVersion","manifestSha","requestedFields"}) {
            var invalid=mapper.readTree(original).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put(field,"different");
            assertThat(ProductSourceCaptureIdentity.normalized(mapper,original))
                    .isNotEqualTo(ProductSourceCaptureIdentity.normalized(mapper,mapper.writeValueAsString(invalid)));
        }
    }
}
