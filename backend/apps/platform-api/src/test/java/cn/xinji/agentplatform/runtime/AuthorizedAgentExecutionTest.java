package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Execution mechanics only; fixture identities are not formal business admission. */
class AuthorizedAgentExecutionTest {
    final Jwt jwt=Jwt.withTokenValue("request").header("alg","RS256").subject("actor").build();
    final JwtDecoder decoder=mock(JwtDecoder.class);
    AuthorizedAgentExecution service() {
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("decoder",decoder);
        when(decoder.decode("request")).thenReturn(jwt);
        return new AuthorizedAgentExecution(beans.getBeanProvider(JwtDecoder.class));
    }
    Checkpoint checkpoint(Phase phase) {var c=mock(Checkpoint.class);when(c.phase()).thenReturn(phase);return c;}
    @Test void completesAndDecodesBeforeEachReadActionAndFinalProjection() {
        var s=service();var actions=new AtomicInteger();var running=checkpoint(Phase.OBSERVE);var stop=checkpoint(Phase.STOP);
        String result=s.execute(jwt,j->actions.get()<2?running:stop,j->actions.incrementAndGet(),j->"actual-result");
        assertEquals("actual-result",result);assertEquals(2,actions.get());verify(decoder,times(6)).decode("request");
    }
    @Test void revokedBetweenReadAndActionNeverExecutesTheAction() {
        var s=service();when(decoder.decode("request")).thenReturn(jwt).thenThrow(new JwtException("revoked"));
        var actions=new AtomicInteger();var projected=new AtomicInteger();
        assertThrows(JwtException.class,()->s.execute(jwt,j->checkpoint(Phase.OBSERVE),j->actions.incrementAndGet(),j->projected.incrementAndGet()));
        assertEquals(0,actions.get());assertEquals(0,projected.get());
    }
    @Test void sourceOrDomainPermissionWithdrawalDoesNotProjectOldResult() {
        var s=service();var projected=new AtomicInteger();
        assertThrows(SecurityException.class,()->s.execute(jwt,j->checkpoint(Phase.ACT),j->{throw new SecurityException("SOURCE_WITHDRAWN");},j->projected.incrementAndGet()));
        assertEquals(0,projected.get());
    }
    @Test void finalProjectionRevalidatesSessionEvenForPreviouslyStoppedRun() {
        var s=service();when(decoder.decode("request")).thenReturn(jwt).thenThrow(new JwtException("expired"));
        assertThrows(JwtException.class,()->s.execute(jwt,j->checkpoint(Phase.STOP),j->fail("action"),j->"secret"));
    }
    @Test void boundedExecutionReturnsCurrentCheckpointWithoutInventingCompletion() {
        var s=service();var actions=new AtomicInteger();var running=checkpoint(Phase.PLAN);
        assertSame(running,s.execute(jwt,j->running,j->actions.incrementAndGet(),j->running));
        assertEquals(512,actions.get());verify(decoder,times(1025)).decode("request");
    }
    @Test void interruptedRequestLeavesResumeToPersistedRunAndPreservesInterruptFlag() {
        var s=service();Thread.currentThread().interrupt();
        try {assertThrows(IllegalStateException.class,()->s.execute(jwt,j->checkpoint(Phase.STOP),j->fail("action"),j->"result"));assertTrue(Thread.currentThread().isInterrupted());verifyNoInteractions(decoder);}
        finally {Thread.interrupted();}
    }
}
