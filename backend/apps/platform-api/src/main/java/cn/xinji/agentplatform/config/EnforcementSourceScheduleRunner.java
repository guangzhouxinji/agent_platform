package cn.xinji.agentplatform.config;

import cn.xinji.agentplatform.enforcement.application.EnforcementSourceManagementService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("test-synthetic")
@ConditionalOnProperty(name = "enforcement.source-admin.scheduling-enabled", havingValue = "true")
public final class EnforcementSourceScheduleRunner {
    private final EnforcementSourceManagementService service;

    public EnforcementSourceScheduleRunner(EnforcementSourceManagementService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${enforcement.source-admin.scan-delay-ms:60000}")
    public void run() {
        service.runDueSchedules();
    }
}
