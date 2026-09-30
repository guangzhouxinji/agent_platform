package cn.xinji.agentplatform.enforcement.procedure;

import static cn.xinji.agentplatform.enforcement.procedure.ProcedureContract.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic calculation over exact, versioned inputs; never supplies legal defaults. */
public final class ProcedureEvaluator {
    public Trial trial(Rule rule, CaseSnapshot snapshot, Calendar calendar) {
        if (rule == null || snapshot == null) throw new IllegalArgumentException("Rule and case required");
        var gaps = new ArrayList<String>();
        var trace = new ArrayList<String>();
        var clock = rule.clock();
        if (!rule.procedure().equals(snapshot.procedure()) || !rule.stage().equals(snapshot.stage()) ||
                !rule.region().equals(snapshot.region()))
            return result("NOT_APPLICABLE", null, trace, List.of(), rule, snapshot, calendar);
        if (rule.basis() == null || rule.basis().effectiveFrom() == null ||
                rule.basis().effectiveTo() == null || snapshot.applicableAt() == null)
            gaps.add("APPLICABILITY_TIME_UNAVAILABLE");
        else if (snapshot.applicableAt().isBefore(rule.basis().effectiveFrom()) ||
                snapshot.applicableAt().isAfter(rule.basis().effectiveTo()))
            return result("NOT_APPLICABLE", null, trace, List.of(), rule, snapshot, calendar);
        if (clock == null || clock.startEvent() == null || clock.startEvent().isBlank() ||
                clock.unit() == null || clock.days() < 1) gaps.add("CLOCK_INCOMPLETE");
        if (snapshot.events() == null) gaps.add("EVENTS_UNAVAILABLE");
        if (clock != null && clock.unit() == Unit.WORKDAY &&
                (calendar == null || !java.util.Objects.equals(clock.calendarRef(), calendar.ref()) ||
                        !java.util.Objects.equals(clock.calendarVersion(), calendar.version()))) gaps.add("CALENDAR_UNAVAILABLE");
        if (!gaps.isEmpty()) return result("UNDETERMINED", null, trace, gaps, rule, snapshot, calendar);
        var events = snapshot.events();
        var starts = events.stream().filter(e -> clock.startEvent().equals(e.kind())).toList();
        if (starts.size() != 1 || starts.getFirst().date() == null)
            gaps.add("START_EVENT_MISSING_OR_AMBIGUOUS:" + clock.startEvent());
        if (events.stream().anyMatch(e -> "INTERRUPTION_CANDIDATE".equals(e.kind())))
            gaps.add("INTERRUPTION_POLICY_UNCONFIRMED");
        if (!gaps.isEmpty()) return result("UNDETERMINED", null, trace, gaps, rule, snapshot, calendar);
        LocalDate start = starts.getFirst().date();
        trace.add("START:" + start + ":" + starts.getFirst().ref() + "@" + starts.getFirst().version());
        var pauses = events.stream().filter(e -> "PAUSE_START".equals(e.kind())).toList();
        var resumes = events.stream().filter(e -> "RESUME".equals(e.kind())).toList();
        if (pauses.size() != resumes.size()) gaps.add("PAUSE_RESUME_UNPAIRED");
        for (int i = 0; i < Math.min(pauses.size(), resumes.size()); i++)
            if (pauses.get(i).date() == null || resumes.get(i).date() == null ||
                    resumes.get(i).date().isBefore(pauses.get(i).date())) gaps.add("PAUSE_RESUME_INVALID");
        if (!pauses.isEmpty() && !"INCLUSIVE_PAUSE_RESUME_NEXT_DAY".equals(clock.pausePolicy()))
            gaps.add("PAUSE_POLICY_UNCONFIRMED");
        if (events.stream().anyMatch(e -> "EXTENSION_APPROVED".equals(e.kind())) && clock.extensionDays() < 1)
            gaps.add("EXTENSION_POLICY_UNCONFIRMED");
        if (!gaps.isEmpty()) return result("UNDETERMINED", null, trace, gaps, rule, snapshot, calendar);
        int extensions = (int) events.stream().filter(e -> "EXTENSION_APPROVED".equals(e.kind())).count();
        if (extensions > 1) return result("UNDETERMINED", null, trace,
                List.of("MULTIPLE_EXTENSIONS_UNDEFINED"), rule, snapshot, calendar);
        int remaining = clock.days() + extensions * clock.extensionDays();
        LocalDate cursor = clock.includeStartDay() ? start.minusDays(1) : start;
        LocalDate due = null;
        // Bounded by calendar coverage for workdays; a malformed input cannot create an unbounded loop.
        for (int n = 0; n < 36600 && remaining > 0; n++) {
            cursor = cursor.plusDays(1);
            if (calendar != null && clock.unit() == Unit.WORKDAY &&
                    (cursor.isBefore(calendar.from()) || cursor.isAfter(calendar.to()))) {
                gaps.add("CALENDAR_OUT_OF_RANGE:" + cursor);
                break;
            }
            boolean paused = false;
            for (int i = 0; i < pauses.size(); i++)
                if (!cursor.isBefore(pauses.get(i).date()) && !cursor.isAfter(resumes.get(i).date())) paused = true;
            if (paused) { trace.add(cursor + ":PAUSED"); continue; }
            if (clock.unit() == Unit.WORKDAY && !working(cursor, calendar)) {
                trace.add(cursor + ":NON_WORKING"); continue;
            }
            trace.add(cursor + ":COUNTED:" + (clock.days() + extensions * clock.extensionDays() - remaining + 1));
            remaining--;
            if (remaining == 0) due = cursor;
        }
        if (due == null) gaps.add("DEADLINE_UNDETERMINED");
        if (due != null && "NEXT_WORKDAY".equals(clock.deadlineAdjustment())) {
            if (calendar == null) gaps.add("ADJUSTMENT_CALENDAR_UNAVAILABLE");
            while (gaps.isEmpty() && !working(due, calendar)) {
                due = due.plusDays(1);
                if (due.isAfter(calendar.to())) gaps.add("CALENDAR_OUT_OF_RANGE:" + due);
                else trace.add(due + ":ADJUSTED");
            }
        } else if (due != null && !"NONE".equals(clock.deadlineAdjustment())) {
            gaps.add("DEADLINE_ADJUSTMENT_UNCONFIRMED");
        }
        return result(gaps.isEmpty() ? "CALCULATED" : "UNDETERMINED",
                gaps.isEmpty() ? due : null, trace, gaps, rule, snapshot, calendar);
    }

    public List<CheckItem> check(Rule rule, CaseSnapshot snapshot, Calendar calendar) {
        var trial = trial(rule, snapshot, calendar);
        if ("NOT_APPLICABLE".equals(trial.state()))
            return List.of(new CheckItem("APPLICABILITY", State.NOT_APPLICABLE, List.of(), List.of(), null, List.of()));
        var result = new ArrayList<CheckItem>();
        Set<String> present = new HashSet<>();
        if (snapshot.events() != null) snapshot.events().forEach(e -> present.add(e.kind()));
        for (var event : rule.requiredEvents())
            result.add(new CheckItem("EVENT:" + event, present.contains(event) ? State.PASS : State.UNDETERMINED,
                    present.contains(event) ? snapshot.events().stream().filter(e -> event.equals(e.kind()))
                            .map(e -> e.ref() + "@" + e.version()).toList() : List.of(),
                    present.contains(event) ? List.of() : List.of("MISSING_EVENT:" + event), null, List.of()));
        for (var material : rule.requiredMaterials())
            result.add(new CheckItem("MATERIAL:" + material,
                    snapshot.materials() != null && snapshot.materials().contains(material) ? State.PASS : State.UNDETERMINED,
                    snapshot.materials() != null && snapshot.materials().contains(material) ? List.of(material) : List.of(),
                    snapshot.materials() != null && snapshot.materials().contains(material) ? List.of() : List.of("MISSING_MATERIAL:" + material),
                    null, List.of()));
        var completed = snapshot.events() == null ? List.<Event>of() : snapshot.events().stream()
                .filter(e -> "STAGE_COMPLETED".equals(e.kind())).toList();
        State deadlineState = State.UNDETERMINED;
        var deadlineGaps = new ArrayList<>(trial.gaps());
        if ("CALCULATED".equals(trial.state())) {
            if (completed.size() != 1 || completed.getFirst().date() == null)
                deadlineGaps.add("COMPLETION_EVENT_MISSING_OR_AMBIGUOUS");
            else deadlineState = completed.getFirst().date().isAfter(trial.dueDate()) ? State.FAIL : State.PASS;
        }
        result.add(new CheckItem("DEADLINE", deadlineState,
                completed.size() == 1 ? List.of(completed.getFirst().ref() + "@" + completed.getFirst().version()) : List.of(),
                List.copyOf(deadlineGaps), trial.dueDate(), trial.trace()));
        return List.copyOf(result);
    }

    private static boolean working(LocalDate date, Calendar calendar) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY &&
                !calendar.nonWorkingDates().contains(date);
    }
    private static Trial result(String state, LocalDate due, List<String> trace, List<String> gaps,
                                Rule rule, CaseSnapshot snapshot, Calendar calendar) {
        return new Trial(state, due, List.copyOf(trace), List.copyOf(gaps), rule.id(), rule.version(),
                snapshot.ref(), snapshot.version(), calendar == null ? null : calendar.ref(),
                calendar == null ? null : calendar.version());
    }
}
