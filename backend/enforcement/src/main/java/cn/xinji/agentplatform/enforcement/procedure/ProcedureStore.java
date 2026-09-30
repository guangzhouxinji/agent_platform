package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;

import java.util.Optional;
import java.util.function.Supplier;

public interface ProcedureStore {
    Optional<Rule> rule(String id, long version);
    Optional<Rule> latestRule(String id);
    java.util.List<Rule> activeRules(String organization, String region, String procedure, String stage);
    Optional<Report> report(String id, long version);
    Optional<Report> latestReport(String id);
    Receipt command(String key, String fingerprint, String actor, String operation,
                    Supplier<Receipt> action);
    void addRule(Rule rule);
    void disableRule(String id, long version);
    void addReport(Report report);
}
