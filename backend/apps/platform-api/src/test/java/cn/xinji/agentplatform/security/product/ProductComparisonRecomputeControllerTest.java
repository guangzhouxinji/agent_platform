package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.xinji.agentplatform.consultation.patentcomparison.PatentComparisonLineage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/** Controlled existing start/auth ports: no physical database, TLS handoff or model dispatch. */
class ProductComparisonRecomputeControllerTest {
    final ProductComparisonLineageProjection lineage=mock(ProductComparisonLineageProjection.class);
    final ProductComparisonStartApplication starts=mock(ProductComparisonStartApplication.class);
    final ProductComparisonStartCommands commands=mock(ProductComparisonStartCommands.class);
    final ProductWorkerDelegations authority=mock(ProductWorkerDelegations.class);
    final ProductComparisonJson json=new ProductComparisonJson(new ObjectMapper().findAndRegisterModules());
    final Instant now=Instant.parse("2026-10-12T00:00:00Z");
    final String input="a".repeat(64),cfg="b".repeat(64),a="c".repeat(64),b="d".repeat(64);
    final RowKey rowA=new RowKey(a,List.of(1),"F1"),rowB=new RowKey(b,List.of(1),"F1");
    final Jwt jwt=Jwt.withTokenValue("component-only").header("alg","RS256").issuer("https://identity.invalid").subject("human").build();
    final ProductComparisonStartApplication.Receipt receipt=new ProductComparisonStartApplication.Receipt("command","task","current",b,input,"key",null,"RECONCILE_REQUIRED",null,now.plusSeconds(30));
    ProductComparisonRecomputeController controller;
    @BeforeEach void setup(){
        controller=new ProductComparisonRecomputeController(lineage,starts,commands,authority,json,Clock.fixed(now,ZoneOffset.UTC));
        when(commands.lookupOriginalKeyMetadata(anyString(),any(),anyString())).thenReturn(Optional.empty());
        when(lineage.read("task","current",jwt)).thenReturn(plan(List.of(rowB),List.of(rowA,rowB),now.plusSeconds(30)));
        when(starts.start("task","current",b,new ProductComparisonStartApplication.Start(2,input),"key",jwt)).thenReturn(receipt);
    }
    ProductComparisonLineageProjection.View plan(List<RowKey> pending,List<RowKey> expected,Instant expiry){return new ProductComparisonLineageProjection.View("task","current",input,"parent","e".repeat(64),"v",cfg,expiry,expected,List.of(),pending,
            List.of(new CarryForwardFrom(rowA,"parent","e".repeat(64),"report","f".repeat(64),"a".repeat(64))),List.of(new ProductComparisonParentEvidenceReader.Ancestor("parent","e".repeat(64),null)));}
    String body(){return json.canonical(new ProductComparisonRecomputeController.Start(2,input,cfg));}
    @Test void onlyPendingBDelegatesUnchangedStartAndNoNewReceipt(){
        assertThat(controller.start("task","current",b,"key",body(),jwt)).isSameAs(receipt);
        var order=inOrder(authority,commands,lineage,starts);order.verify(authority).requireComparisonActor("task",jwt);
        order.verify(commands).lookupOriginalKeyMetadata(anyString(),any(),eq("key"));order.verify(lineage).read("task","current",jwt);
        order.verify(starts).start("task","current",b,new ProductComparisonStartApplication.Start(2,input),"key",jwt);
        verify(commands,never()).reserveExact(any(),any(),anyString(),anyString(),any(),any());
    }
    @Test void fullyCarriedAAndUnknownCandidateCannotStart(){
        assertThatThrownBy(()->controller.start("task","current",a,"key",body(),jwt)).hasMessageContaining("UNAVAILABLE");
        assertThatThrownBy(()->controller.start("task","current","foreign","key",body(),jwt)).hasMessageContaining("UNAVAILABLE");verifyNoInteractions(starts);
    }
    @Test void mixedCandidateIsNotExpandedToEntireModelRequest(){
        var extra=new RowKey(b,List.of(1),"F2");when(lineage.read(anyString(),anyString(),any())).thenReturn(plan(List.of(rowB),List.of(rowA,rowB,extra),now.plusSeconds(30)));
        assertThatThrownBy(()->controller.start("task","current",b,"key",body(),jwt)).hasMessageContaining("UNAVAILABLE");verifyNoInteractions(starts);
    }
    @Test void inputConfigurationAndExactExpiryRefuseBeforeStart(){
        assertThatThrownBy(()->controller.start("task","current",b,"key",json.canonical(new ProductComparisonRecomputeController.Start(2,input,"f".repeat(64))),jwt)).hasMessageContaining("UNAVAILABLE");
        assertThatThrownBy(()->controller.start("task","current",b,"key",json.canonical(new ProductComparisonRecomputeController.Start(2,"f".repeat(64),cfg)),jwt)).hasMessageContaining("UNAVAILABLE");
        when(lineage.read(anyString(),anyString(),any())).thenReturn(plan(List.of(rowB),List.of(rowA,rowB),now));
        assertThatThrownBy(()->controller.start("task","current",b,"key",body(),jwt)).hasMessageContaining("UNAVAILABLE");verifyNoInteractions(starts);
    }
    @Test void originalUnknownKeyUsesOriginalApplicationReplayEvenWhenProjectionIsUnavailable(){
        when(commands.lookupOriginalKeyMetadata(anyString(),any(),anyString())).thenReturn(Optional.of(mock(ProductComparisonStartCommands.Command.class)));
        when(lineage.read(anyString(),anyString(),any())).thenThrow(new IllegalStateException("expired planning evidence"));
        assertThat(controller.start("task","current",b,"key",body(),jwt)).isSameAs(receipt);verifyNoInteractions(lineage);
        verify(starts).start("task","current",b,new ProductComparisonStartApplication.Start(2,input),"key",jwt);
    }
    @Test void replayCoordinatesStillValidatedByOriginalStart(){
        when(commands.lookupOriginalKeyMetadata(anyString(),any(),anyString())).thenReturn(Optional.of(mock(ProductComparisonStartCommands.Command.class)));
        when(starts.start(anyString(),anyString(),anyString(),any(),anyString(),any())).thenThrow(new IllegalStateException("original key mismatch"));
        assertThatThrownBy(()->controller.start("task","current",a,"key",body(),jwt)).hasMessage("original key mismatch");verifyNoInteractions(lineage);
    }
    @Test void noComparePermissionPreventsMetadataReadOrDispatch(){
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND)).when(authority).requireComparisonActor("task",jwt);
        assertThatThrownBy(()->controller.start("task","current",b,"key",body(),jwt)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(commands,lineage,starts);
    }
    @Test void unknownClientFieldsAndBadHashAreRejectedWithoutAuthorityUse(){
        assertThatThrownBy(()->controller.start("task","current",b,"key",body().replace("}",",\"serviceSubject\":\"attacker\"}"),jwt)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.start("task","current",b,"key",json.canonical(new ProductComparisonRecomputeController.Start(2,input,"bad")),jwt)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(authority,commands,lineage,starts);
    }
    @Test void actualPostMappingUsesOriginalReceiptAndStrictBodyWhileGetCannotDispatch() throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt));
        try {
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                    .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build();
            String url="/v2/consultation/tasks/task/patent-comparisons/current/candidates/"+b+"/start-commands/recompute";
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url).header("Idempotency-Key","key").contentType("application/json").content(body()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("RECONCILE_REQUIRED"));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isMethodNotAllowed());
            verify(starts,times(1)).start("task","current",b,new ProductComparisonStartApplication.Start(2,input),"key",jwt);
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
}
