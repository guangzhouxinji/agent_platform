package cn.xinji.agentplatform.consultation.patenttask.application;

import cn.xinji.agentplatform.consultation.patenttask.domain.PatentTaskSnapshot;
import cn.xinji.agentplatform.consultation.patenttask.domain.RecoveryPoint;

public interface RecoveryBoundaryCheck {
    Validation validateFailureRecovery(RecoveryPoint recoveryPoint, PatentTaskSnapshot current);
    Validation validateCancelledRecovery(PatentTaskSnapshot current);

    record Validation(boolean allowed, String reason) {
        public static Validation allow() { return new Validation(true, ""); }
        public static Validation deny(String reason) { return new Validation(false, reason); }
    }
}
