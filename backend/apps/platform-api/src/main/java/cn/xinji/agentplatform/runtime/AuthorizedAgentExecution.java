package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.AgentContract.Checkpoint;
import cn.xinji.agentplatform.runtime.AgentContract.Phase;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Bounded execution inside the authenticated request, NOT a durable background worker.
 * Domain callbacks recreate their context and enforce current permissions/input/source rights.
 * No bearer token is persisted, and no human/official state is decided here. */
public final class AuthorizedAgentExecution {
    public static final int MAX_TRANSITIONS = 512;
    private final ObjectProvider<JwtDecoder> decoders;
    public AuthorizedAgentExecution(ObjectProvider<JwtDecoder> decoders) { this.decoders=decoders; }
    public <V> V execute(Jwt request, Function<Jwt,Checkpoint> read,
                         Consumer<Jwt> advance, Function<Jwt,V> project) {
        for (int transitions=0; transitions<MAX_TRANSITIONS; transitions++) {
            interrupted();
            Checkpoint checkpoint=read.apply(fresh(request));
            if (checkpoint.phase()==Phase.STOP) break;
            interrupted();
            advance.accept(fresh(request));
        }
        interrupted();
        // Final projection also checks the current session/key, even after a STOP/cap.
        return project.apply(fresh(request));
    }
    private Jwt fresh(Jwt request) {
        if(request==null) throw new SecurityException("FORMAL_IDENTITY_REQUIRED");
        return decoders.getObject().decode(request.getTokenValue());
    }
    private static void interrupted() {
        if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("EXECUTION_INTERRUPTED_RECONCILE_RUN");
    }
}
