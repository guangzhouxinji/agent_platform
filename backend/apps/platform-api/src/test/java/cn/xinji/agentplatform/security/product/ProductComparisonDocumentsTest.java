package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.xinji.agentplatform.security.product.ProductComparisonContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Real complete manifest/envelopes through product adapters; authority/deadline ports are mechanical C only. */
class ProductComparisonDocumentsTest {
    @Test void actualCaptureDirectReadPreservesVersionAndSpansAndRejectsAbsentSelectionAndPurpose() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        Path root=Path.of("").toAbsolutePath();while(root!=null && !Files.isDirectory(root.resolve("data/patents"))) root=root.getParent();
        assertThat(root).isNotNull();root=root.resolve("data/patents");
        String manifestRef="patentsview-real-candidate-20261002-v3/manifest.json";
        byte[] manifest=Files.readAllBytes(root.resolve(manifestRef));var tree=mapper.readTree(manifest);
        String sha=ProductCapturedArchive.digest(manifest);
        var active=new ProductSourceAvailability.Active(tree.path("sourceId").asText(),"mechanical-contract",
                "CAPTURE_SHA256:"+sha,"mechanical-decision",1,1,manifestRef,sha,sha);
        var sources=mock(ProductSourceAvailability.class);var access=mock(ProductPatentDocumentAccess.class);
        var authority=new ProductPatentDocumentAccess.Authority("mechanical-scope",1,1,"mechanical-grant",1);
        when(sources.require(anyString(),eq("CLAIMS"),anyString())).thenReturn(active);
        when(sources.requireFields(anyString(),eq(Set.of("CLAIMS")),anyString())).thenReturn(active);
        when(access.require(anyString(),eq(active),any())).thenReturn(authority);
        when(access.requireComparison(anyString(),eq(active),any())).thenReturn(authority);
        Instant now=Instant.parse("2026-10-05T12:00:00Z");var db=mock(JdbcTemplate.class);
        doAnswer(call->call.getArgument(0,String.class).contains("con4_task_source_scope")
                ?List.of(now.plusSeconds(3600)):List.of()).when(db).query(anyString(),any(RowMapper.class),any(Object[].class));
        var files=new ProductSourceEvidenceFiles(root,512L*1024*1024);var originals=new ProductCollectedPatentTitles(files,mapper);
        var documents=new ProductComparisonDocuments(sources,originals,files,access,db,mapper,Clock.fixed(now,ZoneOffset.UTC));
        String rawRef="patentsview-real-candidate-20261002-v3/records/3931897.json";
        String rawSha=ProductCapturedArchive.digest(Files.readAllBytes(root.resolve(rawRef)));
        var ref=new DocumentRef(active.sourceId(),"US_GRANT:3931897","RAW_SHA256:"+rawSha,List.of(1),null,null,null,null);
        var jwt=Jwt.withTokenValue("mechanical-C-only").header("alg","RS256").subject("mechanical-member").build();
        var loaded=documents.read("mechanical-task",ref,jwt,false);
        assertThat(loaded.material().rawSha()).isEqualTo(rawSha);assertThat(loaded.material().gap()).isEmpty();
        assertThat(loaded.material().branches()).hasSize(1);
        var span=loaded.material().branches().getFirst().inheritedClaims().getFirst().span();
        assertThat(span.originalSha()).isEqualTo(rawSha);assertThat(span.byteEnd()).isGreaterThan(span.byteStart());
        assertThat(loaded.original().text()).startsWith("1. In loading apparatus");
        clearInvocations(access);
        var worker=new ProductComparisonWorkerTaskAuthority.Authorized("worker","member","mechanical-task",1,1,now.plusSeconds(3600));
        when(access.requireDelegatedDisplay("mechanical-task",active,worker)).thenReturn(authority);
        when(access.requireDelegatedComparison("mechanical-task",active,worker)).thenReturn(authority);
        var delegated=documents.readDelegated("mechanical-task",ref,worker);
        assertThat(delegated.material()).isEqualTo(loaded.material());
        assertThat(delegated.original().text()).isEqualTo(loaded.original().text());
        verify(access,never()).require(anyString(),any(),any());
        verify(access,never()).requireComparison(anyString(),any(),any());
        var selected=new DocumentRef(ref.sourceId(),ref.documentId(),ref.documentVersion(),List.of(1),"absent-result","absent-selection",1L,"absent-member");
        assertThatThrownBy(()->documents.read("mechanical-task",selected,jwt,false)).isInstanceOf(ResponseStatusException.class);
        when(sources.require(active.sourceId(),"CLAIMS","COMPARISON")).thenThrow(new IllegalStateException("Purpose withdrawn"));
        assertThatThrownBy(()->documents.read("mechanical-task",ref,jwt,false)).hasMessage("Purpose withdrawn");
    }
}
