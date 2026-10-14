package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patenttask.source.PatentFieldSemanticFusion;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Real collected content C plus mechanical guard faults; not a source activation or formal member I. */
class ProductPatentDocumentContentTest {
    private final ProductPatentSelection selections = mock(ProductPatentSelection.class);
    private final ProductSourceAvailability availability = mock(ProductSourceAvailability.class);
    private final ProductPatentDocumentAccess access = mock(ProductPatentDocumentAccess.class);
    private final Instant now = Instant.parse("2026-10-02T20:00:00Z");
    private final Jwt jwt = Jwt.withTokenValue("mechanical-C-only").header("alg", "RS256")
            .subject("mechanical-C-reader").build();
    private ProductSourceAvailability.Active active;
    private ProductPatentSelection.Member member;
    private ProductPatentSelection.Revision selection;
    private PatentFieldSemanticFusion.DocumentEvidence captured;
    private ProductPatentDocumentContent service;

    @BeforeEach void capturedInput() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/08-总开发实现计划.md"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Real capture workspace is required");
        String ref = "data/patents/patentsview-real-candidate-20261002-v3/manifest.json";
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(ref))));
        active = new ProductSourceAvailability.Active("PATENTSVIEW_ZENODO_2024", "mechanical-C-contract",
                "CAPTURE_SHA256:" + sha, "mechanical-C-decision", 1, 1, ref, sha, sha);
        var originals = new ProductCollectedPatentTitles(new ProductSourceEvidenceFiles(root,536870912),new ObjectMapper());
        var corpus = originals.loadFieldEvidence(active,ProductPatentSearchFilters.none(),Set.of("TITLE","CLAIMS"));
        assertThat(corpus).hasSize(40);
        captured = corpus.getFirst(); var doc = captured.document();
        member = new ProductPatentSelection.Member("member-C",0,doc.sourceId(),doc.documentId(),doc.documentVersion(),
                doc.sourceVersion(),doc.rawSha(),captured.fieldLocators().get("TITLE"));
        selection = new ProductPatentSelection.Revision("selection-C","task-C",1,2,"results-C",1,
                "a".repeat(64),"mechanical-C-reader",now,now.plusSeconds(300),List.of(member));
        when(selections.read("task-C","results-C","selection-C",jwt)).thenReturn(selection);
        when(availability.require(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("Mechanical default deny"));
        doReturn(active).when(availability).require(active.sourceId(),"CLAIMS","DISPLAY");
        when(access.require("task-C",active,jwt)).thenReturn(new ProductPatentDocumentAccess.Authority("scope-C",1,2,"grant-C",1));
        service = new ProductPatentDocumentContent(selections,availability,originals,access,Clock.fixed(now,ZoneOffset.UTC));
    }
    private ProductPatentDocumentContent.Content read(String version,String kind,String locator) {
        return service.read("task-C","results-C","selection-C",member.sourceId(),member.documentId(),version,kind,locator,jwt);
    }
    @Test void selectedRealClaimsAreReturnedWithoutTitleOrModelSubstitution() {
        var result = read(member.documentVersion(),"CLAIMS",null);
        assertThat(result.text()).isEqualTo(captured.document().fields().get("CLAIMS"));
        assertThat(result.text()).isNotEqualTo(captured.document().fields().get("TITLE"));
        assertThat(result.locatorRef()).isEqualTo(captured.fieldLocators().get("CLAIMS"));
        assertThat(result.rawSha()).isEqualTo(member.rawSha());
        assertThat(result.sourceVersion()).isEqualTo(member.sourceVersion());
        assertThat(result.parserVersion()).isEqualTo("CAPTURED_JSON_UTF8_TOKEN/v1");
        assertThat(result.retrievedAt()).isEqualTo(now);
        verify(availability,times(2)).require(active.sourceId(),"CLAIMS","DISPLAY");
        verify(selections,times(2)).read("task-C","results-C","selection-C",jwt);
    }
    @Test void exactOriginalLocatorIsAcceptedAndArbitraryFileLocatorIsRejected() {
        assertThat(read(member.documentVersion(),"CLAIMS",captured.fieldLocators().get("CLAIMS")).text()).isNotBlank();
        assertThatThrownBy(() -> read(member.documentVersion(),"CLAIMS","../private#claims"))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }
    @Test void wrongDocumentVersionAndUnselectedDocumentAreHidden() {
        assertThatThrownBy(() -> read("different-version","CLAIMS",null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.read("task-C","results-C","selection-C",member.sourceId(),"unselected",
                member.documentVersion(),"CLAIMS",null,jwt)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(availability);
    }
    @Test void crossTaskUsesTheFormalSelectionGuardBeforeContent() {
        when(selections.read("other-task","results-C","selection-C",jwt)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.read("other-task","results-C","selection-C",member.sourceId(),member.documentId(),
                member.documentVersion(),"CLAIMS",null,jwt)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(availability);
    }
    @Test void revokedDisplayAndUnavailableFullTextNeverBorrowClaims() {
        doThrow(new IllegalStateException("DISPLAY revoked")).when(availability).require(active.sourceId(),"CLAIMS","DISPLAY");
        assertThatThrownBy(() -> read(member.documentVersion(),"CLAIMS",null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> read(member.documentVersion(),"FULL_TEXT",null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void withdrawalBeforeReturnDoesNotReleasePreviouslyReadText() {
        when(availability.require(active.sourceId(),"CLAIMS","DISPLAY")).thenReturn(active)
                .thenThrow(new IllegalStateException("Source withdrawn during read"));
        assertThatThrownBy(() -> read(member.documentVersion(),"CLAIMS",null)).hasMessage("Source withdrawn during read");
    }
    @Test void sourceVersionDriftCannotRebindToCurrentOriginal() {
        var changed = new ProductSourceAvailability.Active(active.sourceId(),active.contractVersion(),"changed-capture",
                active.decisionId(),2,2,active.manifestRef(),active.manifestSha(),active.recordsSha());
        when(availability.require(active.sourceId(),"CLAIMS","DISPLAY")).thenReturn(changed);
        assertThatThrownBy(() -> read(member.documentVersion(),"CLAIMS",null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingJwtAndUnknownKindAreRejected() {
        assertThatThrownBy(() -> service.read("task-C","results-C","selection-C",member.sourceId(),member.documentId(),
                member.documentVersion(),"CLAIMS",null,null)).isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> read(member.documentVersion(),"ABSTRACT",null)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
    @Test void taskReadDoesNotReplaceSourceDisplayAndRevocationBeforeReturnRejects() {
        when(access.require("task-C",active,jwt)).thenThrow(new IllegalStateException("Source DISPLAY revoked"));
        assertThatThrownBy(() -> read(member.documentVersion(),"CLAIMS",null)).hasMessage("Source DISPLAY revoked");
        doReturn(new ProductPatentDocumentAccess.Authority("scope-C",1,2,"grant-C",1))
                .doThrow(new IllegalStateException("Source DISPLAY revoked during read"))
                .when(access).require("task-C",active,jwt);
        assertThatThrownBy(() -> read(member.documentVersion(),"CLAIMS",null)).hasMessage("Source DISPLAY revoked during read");
    }
}
