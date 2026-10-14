package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Mechanical page/storage fault C only. Not a real Source admission or product Worker request. */
final class ProductPatentFieldPageAssertions {
    static void verify(JdbcTemplate db,ObjectMapper mapper,String resultId,ProductPatentFieldResultEvidence.Bundle bundle) {
        var codec=new ProductPatentSearchEvidenceCodec(mapper);
        var members=ProductPatentSearchPages.members(db,codec,resultId);
        var member=members.getFirst(); var facts=bundle.members().get(member.memberId());
        var citation=new ProductPatentSearchCitations.Citation(member.memberId(),member.sourceId(),member.documentId(),
                member.documentVersion(),member.sourceVersion(),"https://example.org/source","https://example.org/raw",
                Instant.parse("2026-09-27T00:00:00Z"),member.rawSha(),member.locatorRef(),facts.locators());
        var titles=mock(ProductCollectedPatentTitles.class);
        var sources=Map.<String,ProductSourceAvailability.Active>of();
        var selected=Set.copyOf(bundle.fields());
        when(titles.fieldCitations(members,sources,selected)).thenReturn(List.of(citation));
        when(titles.fieldCitations(List.of(),sources,selected)).thenReturn(List.of());
        var clock=Clock.fixed(Instant.parse("2026-09-27T00:00:00Z"),ZoneOffset.UTC);
        var pages=new ProductPatentSearchPages(db,codec,
                new ProductPatentSearchCursor(db,new byte[32],Duration.ofMinutes(15),clock),titles,sources);
        var prepared=pages.prepare(resultId,"mechanical-C");
        var page=pages.read(resultId,20,0,prepared);
        assertThat(page.fieldEvidence()).isEqualTo(bundle);
        assertThat(page.members()).isEqualTo(members);
        assertThat(page.citations()).containsExactly(citation);
        assertThat(page.digestVersion()).isEqualTo(ProductPatentFieldResultEvidence.DIGEST_VERSION);
        assertThat(mapper.copy().findAndRegisterModules().valueToTree(page)
                .path("fieldEvidence").path("fields").size()).isEqualTo(selected.size());
        var empty=pages.read(resultId,20,1,prepared);
        assertThat(empty.members()).isEmpty(); assertThat(empty.fieldEvidence().members()).isEmpty();
        assertThat(empty.fieldEvidence().models()).isEqualTo(bundle.models());
        assertThat(empty.resultSha()).isEqualTo(page.resultSha());
        var wrong=new ProductPatentSearchCitations.Citation(citation.memberId(),citation.sourceId(),citation.documentId(),
                citation.documentVersion(),citation.sourceVersion(),citation.sourceUrl(),citation.originalRequestUrl(),
                citation.collectedAt(),citation.rawSha(),citation.locatorRef(),Map.of("ABSTRACT","wrong"));
        when(titles.fieldCitations(members,sources,selected)).thenReturn(List.of(wrong));
        assertThatThrownBy(() -> pages.read(resultId,20,0,prepared))
                .hasMessage("Field page original locators differ from committed evidence");
        when(titles.fieldCitations(members,sources,selected)).thenReturn(List.of());
        assertThatThrownBy(() -> pages.read(resultId,20,0,prepared)).hasMessage("Field page citation count differs");
        String original=db.queryForObject("SELECT fields_json FROM con4_result_field_evidence WHERE result_set_id=?",String.class,resultId);
        db.update("UPDATE con4_result_field_evidence SET fields_json='{}' WHERE result_set_id=?",resultId);
        assertThatThrownBy(() -> pages.read(resultId,20,0,prepared)).hasMessage("Field member evidence digest differs");
        db.update("UPDATE con4_result_field_evidence SET fields_json=? WHERE result_set_id=?",original,resultId);
    }
    private ProductPatentFieldPageAssertions() { }
}
