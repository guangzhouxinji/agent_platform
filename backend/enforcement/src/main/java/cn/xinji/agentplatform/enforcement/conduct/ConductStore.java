package cn.xinji.agentplatform.enforcement.conduct;

import java.util.Optional;
import java.util.function.Supplier;

import static cn.xinji.agentplatform.enforcement.conduct.ConductContract.*;

public interface ConductStore {
    Optional<Rule> rule(String id, long version);
    Optional<Rule> latestRule(String id);
    Optional<Report> report(String id, long version);
    long latestReportVersion(String id);
    Receipt command(String key, String fingerprint, String actor, String operation,
                    Supplier<Receipt> action);
    void addRule(Rule rule);
    void disable(String id, long version);
    void addReport(Report report);
}
