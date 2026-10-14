package cn.xinji.agentplatform.runtime;

import cn.xinji.agentplatform.runtime.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared data-service mechanics only: domain controllers provide live authorization and own their bindings. */
@Component
@ConditionalOnProperty(name="agent-platform.runtime.enabled",havingValue="true")
public final class AgentRunPersistence {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final AgentCheckpointCodec codec;
    public AgentRunPersistence(JdbcTemplate db,PlatformTransactionManager manager,AgentCheckpointCodec codec) {
        this.db=db;this.tx=new TransactionTemplate(manager);this.codec=codec;
    }
    public JdbcAgentCheckpointStore store(JdbcAgentCheckpointStore.Binding binding,JdbcAgentCheckpointStore.CurrentExecution current) {
        return new JdbcAgentCheckpointStore(db,tx,codec,binding,current,Clock.systemUTC());
    }
    /** Caller must authenticate and authorize the exact current domain Task before invoking this read. */
    public JdbcAgentCheckpointStore.Binding binding(String agent,String task,String run,String scope) {
        var rows=db.query("SELECT goal_id,goal_version,expires_at FROM agent_run_head WHERE agent_id=? AND task_id=? AND run_id=? AND scope_ref=?",
                (rs,n)->new JdbcAgentCheckpointStore.Binding(agent,task,rs.getString(1),rs.getString(2),scope,rs.getTimestamp(3).toInstant()),agent,task,run,scope);
        if(rows.size()!=1)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    public static String runId(String agent,String task,String identity,String key) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,120}"))throw new IllegalArgumentException("IDEMPOTENCY_KEY_REQUIRED");
        String framed=agent.length()+":"+agent+task.length()+":"+task+identity.length()+":"+identity+key.length()+":"+key;
        return UUID.nameUUIDFromBytes(framed.getBytes(StandardCharsets.UTF_8)).toString();
    }
    public static String invocationKey(String invocation) {return UUID.nameUUIDFromBytes(invocation.getBytes(StandardCharsets.UTF_8)).toString();}
}
