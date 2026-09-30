package cn.xinji.agentplatform.security.v2;

import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Actor;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.Guard;
import cn.xinji.agentplatform.consultation.taskcore.DevTaskCoreStore.ServiceRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;

public final class DevComparisonInputDm8Support {
    private DevComparisonInputDm8Support() { }

    public static Guard displayGuard(Guard saveGuard, Path manifest) {
        var subjects = DevelopmentBoundaryPolicyTest.subjects();
        var policy = new DevComparisonInputPolicy(new ObjectMapper(), manifest, subjects);
        var subject = subjects.authenticate(DevelopmentBoundaryPolicyTest.token("human-a"), Instant.now());
        if (subject == null) throw new IllegalStateException("Development test subject unavailable");
        return new Guard() {
            @Override public boolean humanCurrent(Actor actor, Instant now) {
                return saveGuard.humanCurrent(actor, now);
            }
            @Override public boolean inputAllowed(Actor actor, String kind, String text, Instant now) {
                return saveGuard.inputAllowed(actor, kind, text, now);
            }
            @Override public boolean inputDisplayAllowed(Actor actor, String kind, String text, Instant now) {
                return actor.principal().equals(subject.stableSubjectId())
                        && actor.organization().equals(subject.organizationRef())
                        && policy.allowsDisplay(subject, kind, text, now);
            }
            @Override public boolean delegationCurrent(String owner, String organization, long version,
                                                       Instant until, Instant now) {
                return saveGuard.delegationCurrent(owner, organization, version, until, now);
            }
            @Override public boolean serviceCurrent(Actor actor, ServiceRole role, Instant now) {
                return saveGuard.serviceCurrent(actor, role, now);
            }
        };
    }
}
