package cn.xinji.agentplatform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreFailure;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.adapter.out.dm8.Dm8DevCurrentWorkStateStore;
import java.time.ZoneId;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class DevCurrentWorkStateDm8Scenarios {
    private final Dm8DevCurrentWorkStateStore first;
    private final Dm8DevCurrentWorkStateStore restarted;
    private final Actor human;
    private final Guard guard;

    DevCurrentWorkStateDm8Scenarios(DriverManagerDataSource source, ZoneId zone, Actor human, Guard guard) {
        this.first = new Dm8DevCurrentWorkStateStore(source, new DataSourceTransactionManager(source), zone);
        this.restarted = new Dm8DevCurrentWorkStateStore(source, new DataSourceTransactionManager(source), zone);
        this.human = human;
        this.guard = guard;
    }

    void empty(String task) { assertThat(first.read(human, task, guard).resultSequence()).isZero(); }

    void restored(String task, String firstSet, String secondSet) {
        var state = restarted.read(human, task, guard);
        assertThat(state.resultSet().setId()).isEqualTo(secondSet);
        assertThat(state.focus().setId()).isEqualTo(firstSet);
        assertThat(state.focus().ordinal()).isEqualTo(2);
        assertThat(state.resultSet().currentInput()).isTrue();
    }

    void historical(String task) {
        assertThat(first.read(human, task, guard).resultSet().currentInput()).isFalse();
        assertThat(restarted.read(human, task, guard).focus().currentInput()).isFalse();
    }

    void withdrawnReference(String task, String setId) {
        assertThat(restarted.read(human, task, guard).resultSet().setId()).isEqualTo(setId);
    }

    void expired(String task) {
        assertThatThrownBy(() -> first.read(human, task, guard))
                .isInstanceOf(DevTaskCoreFailure.class).hasMessage("TASK_EXPIRED");
    }
}
