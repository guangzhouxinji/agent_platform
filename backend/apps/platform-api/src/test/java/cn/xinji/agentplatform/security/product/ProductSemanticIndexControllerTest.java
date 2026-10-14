package cn.xinji.agentplatform.security.product;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

/** Controller wiring C only, not a real maintenance identity or HTTP/DM8 integration receipt. */
class ProductSemanticIndexControllerTest {
    private static final String ROUTE = "/v2/consultation/source-contracts/source/semantic-index";
    @Test void defaultAndExplicitFieldReachTheSameMaintenanceChainAndInvalidFieldIsNotDefaulted() throws Exception {
        var index = mock(ProductSemanticIndex.class); var maintenance = mock(ProductMaintenanceProof.class);
        when(maintenance.require(any(HttpServletRequest.class),eq(ProductMaintenanceProof.Role.SOURCES))).thenReturn("mechanical-controller-actor");
        for (String field : new String[]{"TITLE","ABSTRACT"})
            when(index.buildField("source",field,"key","mechanical-controller-actor"))
                    .thenReturn(new ProductSemanticIndex.Receipt("mechanical-receipt","source",field,"mechanical-model",1,1,0,"a".repeat(64)));
        when(index.buildField("source","","key","mechanical-controller-actor")).thenThrow(new IllegalArgumentException("Empty field"));
        when(index.buildField("source","LEGAL_STATUS","key","mechanical-controller-actor")).thenThrow(new IllegalArgumentException("Unsupported field"));
        var mvc = MockMvcBuilders.standaloneSetup(new ProductSemanticIndexController(index,maintenance)).build();
        mvc.perform(post(ROUTE).header("Idempotency-Key","key")).andExpect(status().isOk()).andExpect(jsonPath("field").value("TITLE"));
        mvc.perform(post(ROUTE).param("field","ABSTRACT").header("Idempotency-Key","key"))
                .andExpect(status().isOk()).andExpect(jsonPath("field").value("ABSTRACT"));
        mvc.perform(post(ROUTE).param("field","").header("Idempotency-Key","key")).andExpect(status().isBadRequest());
        mvc.perform(post(ROUTE).param("field","LEGAL_STATUS").header("Idempotency-Key","key")).andExpect(status().isBadRequest());
        verify(index,times(1)).buildField("source","TITLE","key","mechanical-controller-actor");
        verify(maintenance,times(4)).require(any(HttpServletRequest.class),eq(ProductMaintenanceProof.Role.SOURCES));
    }

    @Test void unauthorizedMaintainerAndUnavailableSourceCannotReturnSuccessfulReceipt() throws Exception {
        var index = mock(ProductSemanticIndex.class); var maintenance = mock(ProductMaintenanceProof.class);
        when(maintenance.require(any(HttpServletRequest.class),eq(ProductMaintenanceProof.Role.SOURCES)))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        var mvc = MockMvcBuilders.standaloneSetup(new ProductSemanticIndexController(index,maintenance)).build();
        mvc.perform(post(ROUTE).param("field","CLAIMS").header("Idempotency-Key","key")).andExpect(status().isForbidden());
        verifyNoInteractions(index);
        when(maintenance.require(any(HttpServletRequest.class),eq(ProductMaintenanceProof.Role.SOURCES))).thenReturn("mechanical-controller-actor");
        when(index.buildField("source","CLAIMS","key","mechanical-controller-actor"))
                .thenThrow(new IllegalStateException("Source field not admitted"));
        mvc.perform(post(ROUTE).param("field","CLAIMS").header("Idempotency-Key","key")).andExpect(status().isServiceUnavailable());
    }
}
