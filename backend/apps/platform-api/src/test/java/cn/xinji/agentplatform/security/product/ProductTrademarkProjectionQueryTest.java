package cn.xinji.agentplatform.security.product;

import static org.junit.jupiter.api.Assertions.*;
import cn.xinji.agentplatform.consultation.trademarklibrary.product.domain.ProductTrademarkLibrary.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductTrademarkProjectionQueryTest {
    private ProductTrademarkProjectionFixture fixture;
    @BeforeEach void setup() throws Exception { fixture = new ProductTrademarkProjectionFixture(); }

    @Test void actualSqlCountAndTwoPagesUseWholeCaptureAndStableOrder() {
        var selected = fixture.project(fixture.input(21));
        var first = read(selected, query("Brand", null, Set.of(), 1));
        var second = read(selected, query("Brand", null, Set.of(), 2));
        assertEquals(21, first.total()); assertEquals(20, first.items().size()); assertTrue(first.hasNext());
        assertEquals(21, second.total()); assertEquals(1, second.items().size()); assertFalse(second.hasNext());
        assertEquals("r00", first.items().getFirst().ref().recordId()); assertEquals("r20", second.items().getFirst().ref().recordId());
        assertEquals("00012A", first.items().getFirst().registrationNumber());
    }
    @Test void multiPartyAndClassExistsDoNotDuplicateAndDetailRetainsAllItems() {
        var selected = fixture.project(fixture.input(21));
        var page = read(selected, query("Brand", "Owner", Set.of("09", "42"), 1));
        assertEquals(21, page.total()); assertEquals(20, page.items().size());
        var view = fixture.inTransaction(() -> fixture.query.detail(List.of(selected), page.items().getFirst().ref()));
        assertEquals(List.of("Owner %_ A", "Owner %_ B"), view.applicants()); assertEquals(2, view.classes().size());
        assertEquals(List.of("First item", "Second item"), view.classes().getFirst().items());
        assertEquals(List.of("0901", "0902"), view.classes().getFirst().similarGroups());
        assertEquals("MONTH", view.validUntil().precision());
    }
    @Test void percentUnderscoreAreLiteralInNameAndPartyQueries() {
        var selected = fixture.project(fixture.input(21));
        assertEquals(21, read(selected, query("%_", "%_", Set.of(), 1)).total());
        assertEquals(0, read(selected, query("%missing_", null, Set.of(), 1)).total());
    }
    @Test void completeDayBoundsAreInclusiveAndPartialValidityDoesNotMatch() {
        var selected = fixture.project(fixture.input(21));
        Query exact = new Query(null, "Brand", null, Set.of(), Set.of("UNMAPPED"), LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 2, 29), null, null, PageRequest.defaults(), false);
        assertEquals(21, read(selected, exact).total());
        Query partial = new Query(null, "Brand", null, Set.of(), Set.of(), null, null,
                LocalDate.of(2034, 2, 1), LocalDate.of(2034, 2, 28), PageRequest.defaults(), false);
        assertEquals(0, read(selected, partial).total());
    }
    @ParameterizedTest @ValueSource(strings = {"name", "party", "class", "token", "raw", "normalized", "binding", "extra-party", "missing-party", "batch"})
    void tamperedOffPageMemberClosesQueryInsteadOfReturningWrongTotal(String mutation) {
        var selected = fixture.project(fixture.input(21)); String id = fixture.versionId("r20");
        switch (mutation) {
            case "name" -> fixture.db.update("UPDATE kb_trademark SET mark_name='Invisible' WHERE record_version_id=?", id);
            case "party" -> fixture.db.update("UPDATE kb_trademark_party SET party_name='Changed' WHERE record_version_id=? AND party_order=0", id);
            case "class" -> fixture.db.update("UPDATE kb_trademark_class SET goods_services='[]' WHERE record_version_id=? AND class_order=0", id);
            case "token" -> fixture.db.update("UPDATE kb_field_provenance SET source_locator='{}' WHERE record_version_id=? AND field_path='/name'", id);
            case "raw" -> fixture.db.update("UPDATE kb_raw_record SET raw_content='{}' WHERE source_record_id='r20'");
            case "normalized" -> fixture.db.update("UPDATE kb_record_version SET normalized_payload='{}' WHERE record_version_id=?", id);
            case "binding" -> fixture.db.update("UPDATE con1tm_record_binding SET relation_sha=? WHERE source_record_id='r20'", "0".repeat(64));
            case "extra-party" -> fixture.db.update("INSERT INTO kb_trademark_party(record_version_id,party_order,party_name) VALUES(?,2,'Extra')", id);
            case "missing-party" -> fixture.db.update("DELETE FROM kb_trademark_party WHERE record_version_id=? AND party_order=1", id);
            case "batch" -> fixture.db.update("UPDATE kb_ingest_batch SET status='FAILED'");
            default -> throw new AssertionError(mutation);
        }
        assertThrows(IllegalStateException.class, () -> read(selected, query("Brand", null, Set.of(), 1)));
    }
    @Test void extraCaptureBindingClosesEntireRead() {
        var selected = fixture.project(fixture.input(21));
        fixture.db.update("INSERT INTO con1tm_record_binding SELECT source_id,contract_version,'extra-record',record_version,registered_source_version,"
                + "record_version_id,raw_ref,raw_sha,raw_bytes,manifest_sha,normalized_sha,relation_sha,map_id,map_version,map_sha,source_nature,"
                + "nature_evidence_ref,nature_evidence_sha,capture_request_id,expires_at FROM con1tm_record_binding WHERE source_record_id='r00'");
        // A duplicate member binding is also an extra member, even when it points at a known V008 version.
        assertThrows(IllegalStateException.class, () -> read(selected, query("Brand", null, Set.of(), 1)));
    }
    @Test void exactDetailNeverSwitchesToLatestOrUnknownVersion() {
        var selected = fixture.project(fixture.input(1));
        var page = read(selected, query("Brand", null, Set.of(), 1)); var ref = page.items().getFirst().ref();
        assertEquals(ref, fixture.inTransaction(() -> fixture.query.detail(List.of(selected), ref)).ref());
        Failure failure = assertThrows(Failure.class, () -> fixture.inTransaction(() -> fixture.query.detail(List.of(selected),
                new RecordRef(ref.sourceId(), ref.recordId(), "wrong-version"))));
        assertEquals(Code.RECORD_NOT_FOUND, failure.code());
        assertEquals(ProductTrademarkProjectionFixture.VERSION, fixture.db.queryForObject("SELECT registered_source_version FROM con1tm_record_binding", String.class));
        assertTrue(ref.version().startsWith("RAW_SHA256:"));
    }
    @Test void unboundLegacyRowsAreNeverCandidates() {
        var selected = fixture.project(fixture.input(1));
        fixture.db.update("DELETE FROM con1tm_record_binding");
        assertThrows(IllegalStateException.class, () -> read(selected, query("Brand", null, Set.of(), 1)));
    }
    @Test void databaseFailureIsNotZeroResultAndForeignDatasourceCannotQuery() {
        var selected = fixture.project(fixture.input(1)); fixture.db.execute("DROP TABLE kb_trademark_party");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> read(selected, query("Brand", null, Set.of(), 1)));
        var other = new org.h2.jdbcx.JdbcDataSource(); other.setURL("jdbc:h2:mem:foreign-trademark");
        assertThrows(IllegalStateException.class, () -> new ProductTrademarkProjectionQuery(new org.springframework.jdbc.core.JdbcTemplate(other), fixture.store, fixture.clock));
    }
    private ProductTrademarkProjectionQuery.Result read(ProductTrademarkProjectionStore.Selection selected, Query query) {
        return fixture.inTransaction(() -> fixture.query.query(List.of(selected), query));
    }
    private static Query query(String name, String party, Set<String> classes, int page) {
        return new Query(null, name, party, classes, Set.of(), null, null, null, null, new PageRequest(page, 20, null), false);
    }
}
