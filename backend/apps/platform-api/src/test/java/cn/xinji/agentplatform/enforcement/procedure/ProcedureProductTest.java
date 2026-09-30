package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;
import static org.assertj.core.api.Assertions.*;

import cn.xinji.agentplatform.enforcement.adapter.out.dm8.Dm8ProcedureStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ProcedureProductTest {
    private static final String ORG = "ORG-ENF-PROJECT";
    @Test void actualProductTransactionsRejectBadPublicationAndKeepImmutableReports() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:enf6-" + System.nanoTime()
                + ";MODE=Oracle;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = data.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource(Path.of("db/dm8/V059__enforcement_v2_procedure.sql")));
        }
        var db = new JdbcTemplate(data);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new Dm8ProcedureStore(db, new TransactionTemplate(new DataSourceTransactionManager(data)),
                mapper, Clock.systemUTC());
        var grant = new AtomicBoolean(true);
        var caseGrant = new AtomicBoolean(true);
        var swappedMaterial = new AtomicReference<String>();
        ProcedureSource source = new ProcedureSource() {
            @Override public CaseSnapshot caseSnapshot(String ref, String version) {
                if ("CASE-FAIL".equals(ref)) throw new IllegalStateException("Upstream unavailable");
                if (!"1".equals(version)) throw new SecurityException("Exact version required");
                List<Event> events = "CASE-MISSING".equals(ref) ? List.of() : List.of(
                        new Event("REQUEST", "1", "REQUEST_RECORDED", date(14), "PROJECT_INPUT"),
                        new Event("COMPLETED", "1", "STAGE_COMPLETED", date(18), "PROJECT_INPUT"));
                return new CaseSnapshot(ref, version, ORG, "PROJECT_TEST", "PROJECT_REVIEW", "STAGE",
                        date(14), events, swappedMaterial.get() == null ? List.of("RECORD@1") :
                                List.of(swappedMaterial.get()), "PROJECT_INPUT_ONLY");
            }
            @Override public Calendar calendar(String ref, String version) {
                if (!"CAL".equals(ref) || !"1".equals(version)) throw new SecurityException("Calendar unavailable");
                return new Calendar(ref, version, date(14), date(22), List.of(date(16)), "PROJECT_NOT_OFFICIAL");
            }
        };
        var service = new ProcedureService(store, source, (actor, org, type, id, version, action, purpose) -> {
            if (!grant.get() || ("CASE".equals(type) && !caseGrant.get()) || !ORG.equals(org) ||
                    !("author".equals(actor) || "reviewer".equals(actor)))
                throw new SecurityException("Current exact grant required");
        }, (basis, rule) -> {
            if (!"PROJECT_VERIFIED_METHOD_ONLY".equals(basis.effectStatus()))
                throw new SecurityException("Unverified basis");
        });
        var rule = new Rule("RULE-1", 0, ORG, "PROJECT_TEST", "PROJECT_REVIEW", "STAGE", null,
                new Basis("PROJECT-METHOD", "1", "section", "PROJECT_VERIFIED_METHOD_ONLY",
                        "PROJECT_TEST", date(14), date(22)),
                new ClockRule(Unit.WORKDAY, 2, "REQUEST_RECORDED", false, "NEXT_WORKDAY",
                        "CAL", "1", "INCLUSIVE_PAUSE_RESUME_NEXT_DAY", "NONE", 0),
                List.of("REQUEST_RECORDED"), List.of("RECORD@1"), null, null, null);
        var draft = service.draft("author", "draft-key", rule);
        assertThat(draft.version()).isEqualTo(1);
        assertThat(service.draft("author", "draft-key", rule)).isEqualTo(draft);
        var changedDraft = new Rule(rule.id(), 0, rule.organization(), rule.region(), rule.procedure(),
                rule.stage(), null, rule.basis(), rule.clock(), rule.requiredEvents(),
                List.of("OTHER-ORIGINAL@1"), null, null, null);
        assertThatThrownBy(() -> service.draft("author", "draft-key", changedDraft))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(1);
        assertThat(service.trial("author", "RULE-1", 1,
                new ProcedureService.TrialRequest("CASE-1", "1", "CAL", "1")).dueDate()).isEqualTo(date(17));
        var publication = new ProcedureService.Publication(
                List.of(new ProcedureService.TrialRequest("CASE-1", "1", "CAL", "1")),
                List.of(new ProcedureService.TrialRequest("CASE-MISSING", "1", "CAL", "1")),
                "PROJECT-REVIEW-1", "reviewer");
        assertThatThrownBy(() -> service.publish("author", "self-review", "RULE-1", 1,
                new ProcedureService.Publication(publication.positive(), publication.negative(),
                        publication.reviewRef(), "author"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(1);
        assertThat(service.publish("reviewer", "publish-key", "RULE-1", 1, publication).version()).isEqualTo(2);
        assertThat(service.readRule("author", "RULE-1", 1).state()).isEqualTo("DRAFT");
        assertThat(service.readRule("author", "RULE-1", 2).state()).isEqualTo("ACTIVE");
        var request = new ProcedureService.CheckRequest("CASE-1", "1", "RULE-1", 2, "CAL", "1");
        assertThatThrownBy(() -> service.check("author", "source-503", "REPORT-FAIL",
                new ProcedureService.CheckRequest("CASE-FAIL", "1", "RULE-1", 2, "CAL", "1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(2);
        assertThat(service.check("author", "check-key", "REPORT-1", request).version()).isEqualTo(1);
        assertThat(service.readReport("author", "REPORT-1", 1).ruleVersion()).isEqualTo(2);
        assertThat(service.readReport("author", "REPORT-1", 1).items().getLast().state()).isEqualTo(State.FAIL);
        swappedMaterial.set("OTHER-ORIGINAL@1");
        assertThatThrownBy(() -> service.readReport("author", "REPORT-1", 1))
                .isInstanceOf(SecurityException.class).hasMessageContaining("references changed");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(3);
        swappedMaterial.set(null);
        assertThat(service.rectify("reviewer", "rectify-key", "REPORT-1",
                new ProcedureService.Rectification("Provide corrected source", List.of("RECORD@2"), 1)).version())
                .isEqualTo(2);
        assertThat(service.rectify("reviewer", "rectify-key", "REPORT-1",
                new ProcedureService.Rectification("Provide corrected source", List.of("RECORD@2"), 1)).version())
                .isEqualTo(2);
        caseGrant.set(false);
        assertThatThrownBy(() -> service.recheck("reviewer", "recheck-no-case-grant", "REPORT-1", 2, request))
                .isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(4);
        caseGrant.set(true);
        assertThat(service.recheck("reviewer", "recheck-key", "REPORT-1", 2, request).version()).isEqualTo(3);
        assertThat(service.recheck("reviewer", "recheck-key", "REPORT-1", 2, request).version()).isEqualTo(3);
        assertThat(service.readReport("author", "REPORT-1", 1).rectification()).isNull();
        assertThat(service.readReport("author", "REPORT-1", 3).previousVersion()).isEqualTo(2);
        assertThatThrownBy(() -> service.check("author", "bad-key", "REPORT-2",
                new ProcedureService.CheckRequest("CASE-1", "2", "RULE-1", 2, "CAL", "1")))
                .isInstanceOf(SecurityException.class);
        assertThat(service.disable("reviewer", "disable-key", "RULE-1", 2).version()).isEqualTo(2);
        assertThat(service.readRule("author", "RULE-1", 2).state()).isEqualTo("DISABLED");
        assertThat(service.readReport("author", "REPORT-1", 1).ruleVersion()).isEqualTo(2);
        assertThatThrownBy(() -> service.check("author", "disabled-key", "REPORT-2", request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(6);
        grant.set(false);
        assertThatThrownBy(() -> service.check("author", "revoked-key", "REPORT-2", request))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.check("author", "check-key", "REPORT-1", request))
                .isInstanceOf(SecurityException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_report", Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_receipt", Integer.class)).isEqualTo(6);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM enf6_procedure_event", Integer.class)).isEqualTo(6);
    }
    private static LocalDate date(int day) { return LocalDate.of(2026, 9, day); }
}
