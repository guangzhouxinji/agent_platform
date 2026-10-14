package cn.xinji.agentplatform.security.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;

/** Ordering and response-minimization contract; certificate/SQL and binding use separate real-store tests. */
class ProductComparisonWorkerIntentAccessTest {
    final ProductComparisonWorkerTaskAuthority authority=mock(ProductComparisonWorkerTaskAuthority.class);
    final ProductComparisonWorkerDelegations bindings=mock(ProductComparisonWorkerDelegations.class);
    final ProductComparisonWorkerIntentAccess access=new ProductComparisonWorkerIntentAccess(authority,bindings);
    final HttpServletRequest request=mock(HttpServletRequest.class);
    final String sha="a".repeat(64);
    final ProductComparisonWorkerTaskAuthority.Authorized actor=new ProductComparisonWorkerTaskAuthority.Authorized(
            "service","human","task",3,4,Instant.parse("2026-10-07T12:00:00Z"));
    final ProductComparisonWorkerDelegations.Receipt receipt=new ProductComparisonWorkerDelegations.Receipt(
            "delegation",1,"ACTIVE","comparison",sha,"candidate",actor.expiresAt());
    ProductComparisonWorkerDelegations.Receipt run() {
        return access.require(request,"task","comparison","candidate",sha,"delegation");
    }
    void ready() {
        when(authority.require(request,"task","delegation")).thenReturn(actor);
        when(bindings.currentWorkerIntent("task","comparison","candidate",sha,"delegation","service",3)).thenReturn(receipt);
    }
    @Test void authenticatesBeforeIntentAndRechecksBothBoundaries() {
        ready();assertThat(run()).isEqualTo(receipt);
        var ordered=inOrder(authority,bindings);
        ordered.verify(authority).require(request,"task","delegation");
        ordered.verify(bindings).currentWorkerIntent("task","comparison","candidate",sha,"delegation","service",3);
        ordered.verify(authority).recheck(request,"task","delegation",actor);
        ordered.verify(bindings).currentWorkerIntent("task","comparison","candidate",sha,"delegation","service",3);
        ordered.verify(authority).recheck(request,"task","delegation",actor);ordered.verifyNoMoreInteractions();
    }
    @Test void identityFailureCannotReadEvenIntent() {
        when(authority.require(request,"task","delegation")).thenThrow(new IllegalStateException("identity denied"));
        assertThatThrownBy(this::run).hasMessage("identity denied");verifyNoInteractions(bindings);
    }
    @Test void driftAfterFirstBindingStopsBeforeSecondRead() {
        ready();doThrow(new IllegalStateException("authorization changed")).when(authority).recheck(request,"task","delegation",actor);
        assertThatThrownBy(this::run).hasMessage("authorization changed");
        verify(bindings,times(1)).currentWorkerIntent("task","comparison","candidate",sha,"delegation","service",3);
    }
    @Test void bindingChangeCannotReturnPreviouslyActiveReceipt() {
        ready();when(bindings.currentWorkerIntent("task","comparison","candidate",sha,"delegation","service",3))
                .thenReturn(receipt,new ProductComparisonWorkerDelegations.Receipt("delegation",2,"REVOKED","comparison",sha,"candidate",actor.expiresAt()));
        assertThatThrownBy(this::run).hasMessage("Comparison worker intent changed");
    }
    @Test void finalAuthorityChangeCannotReturnReceipt() {
        ready();doNothing().doThrow(new IllegalStateException("final authorization changed"))
                .when(authority).recheck(request,"task","delegation",actor);
        assertThatThrownBy(this::run).hasMessage("final authorization changed");
    }
}
