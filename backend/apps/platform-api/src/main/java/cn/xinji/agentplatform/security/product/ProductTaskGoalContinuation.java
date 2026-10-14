package cn.xinji.agentplatform.security.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Versioned goal changes reuse the same Task; an unchanged search does not consume another execution. */
final class ProductTaskGoalContinuation {
    record Key(String goalId, long revision) { }
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    ProductTaskGoalContinuation(JdbcTemplate db, ObjectMapper mapper) {this.db=db;this.mapper=mapper;}

    Key next(String taskId, String state, long version, ProductTaskGoalService.Input input,
             ProductPatentSearchFilters filters) {
        if (version != input.expectedTaskVersion() || version == Long.MAX_VALUE) throw conflict();
        if ("AWAITING_GOAL".equals(state)) return new Key(UUID.randomUUID().toString(),1);
        if (!"IN_PROGRESS".equals(state)) throw conflict();
        Integer pending = db.queryForObject("SELECT COUNT(*) FROM con3_execution_lease "
                + "WHERE task_id=? AND status<>'COMMITTED'", Integer.class, taskId);
        Integer dispatch = db.queryForObject("SELECT COUNT(*) FROM con3_execution_dispatch WHERE task_id=?",
                Integer.class, taskId);
        if (pending == null || pending != 0 || dispatch == null || dispatch != 0) throw conflict();
        List<Key> current = db.query("SELECT g.goal_id,g.revision,g.purpose,g.object_text,g.scope_json,"
                        + "g.features_json,i.requested_fields_json,i.filters_json FROM con3_goal_current c "
                        + "JOIN con3_goal_revision g ON g.task_id=c.task_id AND g.goal_id=c.goal_id "
                        + "AND g.revision=c.revision JOIN con3_search_input i ON i.task_id=c.task_id "
                        + "AND i.goal_id=c.goal_id AND i.goal_revision=c.revision AND i.task_version=c.task_version "
                        + "WHERE c.task_id=? AND c.task_version=?", (rs,row)-> {
                    if (input.purpose().equals(rs.getString(3))
                            && normalize(input.queryObject()).equalsIgnoreCase(normalize(rs.getString(4)))
                            && same(input.sourceIds(),rs.getString(5))
                            && same(input.requiredFeatures(),rs.getString(6))
                            && same(input.requestedFields(),rs.getString(7))
                            && filters.equals(ProductPatentSearchFilters.parse(mapper,rs.getString(8)))) throw conflict();
                    long revision=rs.getLong(2);
                    if(revision==Long.MAX_VALUE) throw conflict();
                    return new Key(rs.getString(1),revision+1);
                },taskId,version);
        if(current.size()!=1) throw conflict();
        return current.getFirst();
    }
    private boolean same(List<String> value,String json) {
        try {return new HashSet<>(value).equals(new HashSet<>(mapper.readValue(json,List.class)));}
        catch(java.io.IOException invalid){throw new IllegalStateException("Persisted goal content invalid",invalid);}
    }
    private static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFC).strip().replaceAll("\\s+"," ");}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT);}
}
