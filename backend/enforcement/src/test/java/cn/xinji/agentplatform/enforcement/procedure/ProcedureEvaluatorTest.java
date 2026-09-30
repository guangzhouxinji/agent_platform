package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProcedureEvaluatorTest {
    private final ProcedureEvaluator evaluator = new ProcedureEvaluator();
    private final Calendar calendar = new Calendar("PROJECT-CALENDAR-01", "1", date(14), date(22),
            List.of(date(16), date(19), date(20)), "PROJECT_TEST_NOT_OFFICIAL");

    @Test void fixedCalendarAndPauseExtensionTrace() {
        var base = evaluator.trial(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14))), calendar);
        assertEquals("CALCULATED", base.state());
        assertEquals(date(17), base.dueDate());
        var extended = evaluator.trial(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("PAUSE_START", 15), event("RESUME", 17), event("EXTENSION_APPROVED", 18))), calendar);
        assertEquals(date(22), extended.dueDate());
        assertTrue(extended.trace().contains("2026-09-16:PAUSED"));
        assertTrue(extended.trace().contains("2026-09-19:NON_WORKING"));
    }

    @Test void missingUnknownAndWrongProcedureNeverPass() {
        var missing = evaluator.check(rule(), snapshot(List.of()), calendar);
        assertEquals(State.UNDETERMINED, missing.getFirst().state());
        assertEquals(State.UNDETERMINED, missing.getLast().state());
        assertEquals("UNDETERMINED", evaluator.trial(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14))), null).state());
        var interrupt = evaluator.trial(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("INTERRUPTION_CANDIDATE", 18))), calendar);
        assertEquals("UNDETERMINED", interrupt.state());
        assertTrue(interrupt.gaps().contains("INTERRUPTION_POLICY_UNCONFIRMED"));
        var other = new CaseSnapshot("case", "1", "org", "region", "OTHER", "stage", date(14),
                List.of(event("REQUEST_RECORDED", 14)), List.of("MATERIAL@1"), "source");
        assertEquals(State.NOT_APPLICABLE, evaluator.check(rule(), other, calendar).getFirst().state());
        var outside = new CaseSnapshot("case", "1", "org", "region", "REVIEW", "stage", date(23),
                List.of(event("REQUEST_RECORDED", 14)), List.of("MATERIAL@1"), "source");
        assertEquals(State.NOT_APPLICABLE, evaluator.check(rule(), outside, calendar).getFirst().state());
        var unknownTime = new CaseSnapshot("case", "1", "org", "region", "REVIEW", "stage", null,
                List.of(event("REQUEST_RECORDED", 14)), List.of("MATERIAL@1"), "source");
        assertEquals("UNDETERMINED", evaluator.trial(rule(), unknownTime, calendar).state());
    }

    @Test void deadlineRequiresObservedCompletionAndCanFail() {
        var absent = evaluator.check(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14))), calendar);
        assertEquals(State.UNDETERMINED, absent.getLast().state());
        assertTrue(absent.getLast().gaps().contains("COMPLETION_EVENT_MISSING_OR_AMBIGUOUS"));
        var late = evaluator.check(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("STAGE_COMPLETED", 18))), calendar);
        assertEquals(State.FAIL, late.getLast().state());
        assertEquals(date(17), late.getLast().dueDate());
        var onTime = evaluator.check(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("STAGE_COMPLETED", 17))), calendar);
        assertEquals(State.PASS, onTime.getLast().state());
    }

    @Test void ambiguousStartInvalidPauseAndInsufficientCalendarStayUndetermined() {
        var duplicateStart = evaluator.check(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("REQUEST_RECORDED", 15), event("STAGE_COMPLETED", 17))), calendar);
        assertEquals(State.UNDETERMINED, duplicateStart.getLast().state());
        assertTrue(duplicateStart.getLast().gaps().stream()
                .anyMatch(gap -> gap.startsWith("START_EVENT_MISSING_OR_AMBIGUOUS")));

        var unpairedPause = evaluator.check(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("PAUSE_START", 15), event("STAGE_COMPLETED", 17))), calendar);
        assertEquals(State.UNDETERMINED, unpairedPause.getLast().state());
        assertTrue(unpairedPause.getLast().gaps().contains("PAUSE_RESUME_UNPAIRED"));

        var invalidOrder = evaluator.check(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("PAUSE_START", 18), event("RESUME", 17), event("STAGE_COMPLETED", 17))), calendar);
        assertEquals(State.UNDETERMINED, invalidOrder.getLast().state());
        assertTrue(invalidOrder.getLast().gaps().contains("PAUSE_RESUME_INVALID"));

        var shortCalendar = new Calendar(calendar.ref(), calendar.version(), date(14), date(15),
                List.of(), "PROJECT_TEST_NOT_OFFICIAL");
        var outsideCoverage = evaluator.check(rule(), snapshot(List.of(event("REQUEST_RECORDED", 14),
                event("STAGE_COMPLETED", 17))), shortCalendar);
        assertEquals(State.UNDETERMINED, outsideCoverage.getLast().state());
        assertNull(outsideCoverage.getLast().dueDate());
        assertTrue(outsideCoverage.getLast().gaps().stream()
                .anyMatch(gap -> gap.startsWith("CALENDAR_OUT_OF_RANGE")));
    }

    private Rule rule() {
        return new Rule("rule", 1, "org", "region", "REVIEW", "stage", "DRAFT",
                new Basis("basis", "1", "section", "PROJECT_ONLY", "scope", date(14), date(22)),
                new ClockRule(Unit.WORKDAY, 2, "REQUEST_RECORDED", false, "NEXT_WORKDAY",
                        calendar.ref(), calendar.version(), "INCLUSIVE_PAUSE_RESUME_NEXT_DAY", "UNCONFIRMED", 1),
                List.of("REQUEST_RECORDED"), List.of("MATERIAL@1"), "author", null, null);
    }
    private CaseSnapshot snapshot(List<Event> events) {
        return new CaseSnapshot("case", "1", "org", "region", "REVIEW", "stage", date(14),
                events, List.of("MATERIAL@1"), "source");
    }
    private Event event(String kind, int day) { return new Event(kind + day, "1", kind, date(day), "project"); }
    private LocalDate date(int day) { return LocalDate.of(2026, 9, day); }
}
