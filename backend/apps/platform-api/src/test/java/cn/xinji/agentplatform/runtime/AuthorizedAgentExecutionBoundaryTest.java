package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.AgentContract.*;
import cn.xinji.agentplatform.runtime.AgentPorts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Independent boundary review C: mechanical fixtures, NOT formal identity/source/DM8 acceptance. */
class AuthorizedAgentExecutionBoundaryTest {
    final Jwt request=token("incoming");
    final JwtDecoder decoder=mock(JwtDecoder.class);
    Jwt token(String marker) { return Jwt.withTokenValue("raw-request").header("alg","RS256").subject(marker).build(); }
    AuthorizedAgentExecution execution() {
        var beans=new DefaultListableBeanFactory(); beans.registerSingleton("decoder",decoder);
        return new AuthorizedAgentExecution(beans.getBeanProvider(JwtDecoder.class));
    }
    Checkpoint phase(Phase phase) { var c=mock(Checkpoint.class); when(c.phase()).thenReturn(phase); return c; }

    @Test void callbacksReceiveEachFreshDecodeRatherThanTheIncomingPrincipal() {
        Jwt readJwt=token("read"),actionJwt=token("advance"),stopJwt=token("stop"),outputJwt=token("output");
        when(decoder.decode("raw-request")).thenReturn(readJwt,actionJwt,stopJwt,outputJwt);
        var reads=new AtomicInteger(); var actions=new AtomicInteger();
        String result=execution().execute(request,j->{
            if(reads.getAndIncrement()==0) { assertSame(readJwt,j); return phase(Phase.OBSERVE); }
            assertSame(stopJwt,j); return phase(Phase.STOP);
        },j->{assertSame(actionJwt,j); actions.incrementAndGet();},j->{assertSame(outputJwt,j); return j.getSubject();});
        assertEquals("output",result); assertEquals(1,actions.get()); verify(decoder,times(4)).decode("raw-request");
    }

    @Test void revocationAfterCommittedTransitionRejectsNextReadAndDoesNotProject() {
        when(decoder.decode("raw-request")).thenReturn(request);
        var committed=new AtomicInteger(); var reads=new AtomicInteger(); var outputs=new AtomicInteger();
        assertThrows(BadJwtException.class,()->execution().execute(request,j->{reads.incrementAndGet();return phase(Phase.PLAN);},j->{
            committed.incrementAndGet(); when(decoder.decode("raw-request")).thenThrow(new BadJwtException("REVOKED"));
        },j->outputs.incrementAndGet()));
        assertEquals(1,committed.get()); assertEquals(1,reads.get()); assertEquals(0,outputs.get());
        verify(decoder,times(3)).decode("raw-request");
    }

    @Test void sourceWithdrawalAtFinalDomainProjectionNeverReturnsRetainedCheckpoint() {
        when(decoder.decode("raw-request")).thenReturn(request);
        var retained=phase(Phase.STOP); var projections=new AtomicInteger();
        var denied=assertThrows(SecurityException.class,()->execution().execute(request,j->retained,j->fail("STOP must not advance"),j->{
            projections.incrementAndGet(); throw new SecurityException("SOURCE_WITHDRAWN");
        }));
        assertEquals("SOURCE_WITHDRAWN",denied.getMessage()); assertEquals(1,projections.get());
    }

    @Test void missingIdentityOrMissingDecoderNeverCallsDomainCallbacks() {
        var s=execution();
        assertThrows(SecurityException.class,()->s.execute(null,j->fail("read"),j->fail("advance"),j->fail("project")));
        verifyNoInteractions(decoder);
        var empty=new DefaultListableBeanFactory();
        var unavailable=new AuthorizedAgentExecution(empty.getBeanProvider(JwtDecoder.class));
        assertThrows(org.springframework.beans.factory.NoSuchBeanDefinitionException.class,
                ()->unavailable.execute(request,j->fail("read"),j->fail("advance"),j->fail("project")));
    }

    @Test void interruptionAfterReservationResumesSamePendingInvocationWithoutInventingStop() {
        when(decoder.decode("raw-request")).thenReturn(request);
        var f=new RuntimeFixture(); f.toAct(); var projections=new AtomicInteger();
        try {
            assertThrows(IllegalStateException.class,()->execution().execute(request,j->f.store.load("run"),j->{
                f.runtime().advance("run"); Thread.currentThread().interrupt();
            },j->projections.incrementAndGet()));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0,projections.get());
            assertEquals(Phase.ACT,f.store.head.get().phase()); assertEquals("run/1",f.store.head.get().pending().id());
            assertEquals(0,f.effects.get());
        } finally { Thread.interrupted(); }
        var resumed=execution().execute(request,j->f.store.load("run"),j->f.runtime().advance("run"),j->f.store.load("run"));
        assertEquals(StopReason.NEED_HUMAN,resumed.outcome().reason()); assertEquals(1,f.effects.get());
        assertEquals(Set.of("run/1"),f.receipts.keySet());
    }

    @Test void concurrentPendingReplayUsesSameReceiptAndCasLoserNeverProjects() throws Exception {
        when(decoder.decode("raw-request")).thenReturn(request);
        var f=new RuntimeFixture(); f.toAct(); f.runtime().advance("run");
        f.rendezvous=new CyclicBarrier(2); var projected=new AtomicInteger(); var pool=Executors.newFixedThreadPool(2);
        var s=execution(); int successful=0,conflicts=0;
        try {
            Callable<Checkpoint> call=()->s.execute(request,j->f.store.load("run"),j->f.runtime().advance("run"),j->{
                projected.incrementAndGet(); return f.store.load("run");
            });
            var first=pool.submit(call); var second=pool.submit(call);
            for(var future:List.of(first,second)) {
                try { assertEquals(Phase.STOP,future.get(10,TimeUnit.SECONDS).phase()); successful++; }
                catch(ExecutionException failure) { assertEquals("CAS_CONFLICT",failure.getCause().getMessage()); conflicts++; }
            }
            assertEquals(1,successful); assertEquals(1,conflicts); assertEquals(1,projected.get());
            assertEquals(2,f.invokes.get()); assertEquals(1,f.effects.get()); assertEquals(Set.of("run/1"),f.receipts.keySet());
            assertEquals(f.store.head.get(),s.execute(request,j->f.store.load("run"),j->fail("stopped replay"),j->f.store.load("run")));
            assertEquals(2,f.invokes.get());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS)); }
    }

    /** Deliberate in-memory CAS and idempotent domain stand-ins; no database/external source claims. */
    static final class RuntimeFixture {
        final Instant now=Instant.parse("2026-10-06T00:00:00Z");
        final Definition definition=new Definition("review","v1",Set.of("review"),Map.of("organize",Set.of("read")),"p1","a1","e1");
        final Step step=new Step("read","organize","read","input/v1");
        final MemoryStore store=new MemoryStore(); final AtomicInteger invokes=new AtomicInteger(),effects=new AtomicInteger();
        final ConcurrentMap<String,Observation> receipts=new ConcurrentHashMap<>(); volatile CyclicBarrier rendezvous;
        AgentRuntime runtime() {
            return new AgentRuntime(definition,store,new Skills() {
                public Observation observe(Checkpoint c) { return new Observation("input/v1","a".repeat(64)); }
                public Plan plan(Checkpoint c) { return new Plan("v1",List.of(step)); }
                public Observation invoke(Checkpoint c,Invocation invocation) {
                    invokes.incrementAndGet();
                    if(rendezvous!=null) try { rendezvous.await(5,TimeUnit.SECONDS); }
                    catch(Exception failure) { throw new AssertionError("Concurrent request did not reach same pending invocation",failure); }
                    return receipts.computeIfAbsent(invocation.id(),id->{effects.incrementAndGet();return new Observation("receipt:"+id,"b".repeat(64));});
                }
            },(c,s)->true,c->new Evaluation(Verdict.NEED_HUMAN,"human-review"),Clock.fixed(now,ZoneOffset.UTC));
        }
        void toAct() {
            var runtime=runtime();
            runtime.start("run",new Task("task","review","scope",new Goal("goal","1","input-ref","human-review")),new Budget(2,0,Duration.ofMinutes(1)));
            runtime.advance("run"); runtime.advance("run");
        }
    }
    static final class MemoryStore implements CheckpointStore {
        final AtomicReference<Checkpoint> head=new AtomicReference<>();
        public Checkpoint load(String runId) { return head.get(); }
        public void commit(long expected,Checkpoint next,AgentEvent event) {
            var current=head.get();
            if((current==null?-1:current.version())!=expected || !head.compareAndSet(current,next))throw new IllegalStateException("CAS_CONFLICT");
        }
    }
}