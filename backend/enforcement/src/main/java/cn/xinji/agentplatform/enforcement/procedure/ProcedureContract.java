package cn.xinji.agentplatform.enforcement.procedure;

import java.time.LocalDate;
import java.util.List;

public final class ProcedureContract {
    private ProcedureContract() { }

    public enum Unit { CALENDAR_DAY, WORKDAY }
    public enum State { PASS, FAIL, UNDETERMINED, NOT_APPLICABLE }
    public record Basis(String ref, String version, String locator, String effectStatus,
                        String scope, LocalDate effectiveFrom, LocalDate effectiveTo) { }
    public record ClockRule(Unit unit, int days, String startEvent, boolean includeStartDay,
                            String deadlineAdjustment, String calendarRef, String calendarVersion,
                            String pausePolicy, String interruptionPolicy, int extensionDays) { }
    public record Rule(String id, long version, String organization, String region,
                       String procedure, String stage, String state, Basis basis,
                       ClockRule clock, List<String> requiredEvents, List<String> requiredMaterials,
                       String author, String reviewer, String reviewRef) { }
    public record Event(String ref, String version, String kind, LocalDate date, String sourceRef) { }
    public record Calendar(String ref, String version, LocalDate from, LocalDate to,
                           List<LocalDate> nonWorkingDates, String sourceRef) { }
    public record CaseSnapshot(String ref, String version, String organization, String region,
                               String procedure, String stage, LocalDate applicableAt,
                               List<Event> events, List<String> materials, String sourceRef) { }
    public record Trial(String state, LocalDate dueDate, List<String> trace,
                        List<String> gaps, String ruleId, long ruleVersion,
                        String caseRef, String caseVersion, String calendarRef, String calendarVersion) { }
    public record CheckItem(String requirement, State state, List<String> inputRefs,
                            List<String> gaps, LocalDate dueDate, List<String> trace) { }
    public record Report(String id, long version, String organization, String caseRef,
                         String caseVersion, String ruleId, long ruleVersion, String calendarRef,
                         String calendarVersion, String executionState, List<CheckItem> items,
                         List<String> unprocessed, String rectification, String rectifiedBy,
                         long previousVersion) { }
    public record Receipt(String requestId, String operation, String resultId, long version) { }
}
