package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;

public record TaskCommandResult(Outcome outcome, PatentTaskSnapshot snapshot) {
    public enum Outcome { APPLIED, REPLAYED }
}
