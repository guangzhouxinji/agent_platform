package cn.xinji.agentplatform;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgentPlatformApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "--product-consultation".equals(args[0])) {
            SpringApplication.run(cn.xinji.agentplatform.security.product.ProductConsultationApplication.class,
                    Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "--product-enforcement".equals(args[0])) {
            SpringApplication.run(cn.xinji.agentplatform.security.product.ProductEnforcementApplication.class,
                    Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "--identity-bootstrap".equals(args[0])) {
            if (args.length != 2) throw new IllegalArgumentException("Identity bootstrap requires one manifest path");
            try {
                cn.xinji.agentplatform.security.product.ProductIdentityBootstrap.main(new String[] {args[1]});
            } catch (Exception failure) {
                throw new IllegalStateException("Identity bootstrap failed closed", failure);
            }
            return;
        }
        if (args.length > 0 && "--task-recovery-verify".equals(args[0])) {
            if (args.length != 1) throw new IllegalArgumentException("Task recovery check takes no arguments");
            cn.xinji.agentplatform.security.product.ProductTaskRecoveryCheck.main(new String[0]);
            return;
        }
        if (args.length > 0 && "--task-backup".equals(args[0])) {
            if (args.length != 2) throw new IllegalArgumentException("Task backup requires seal or open");
            try {
                cn.xinji.agentplatform.security.product.ProductTaskBackupCommand.main(new String[] {args[1]});
            } catch (Exception failure) {
                throw new IllegalStateException("Task backup failed closed", failure);
            }
            return;
        }
        SpringApplication application = new SpringApplication(AgentPlatformApplication.class);
        if (!hasDataSource(args)) {
            application.setDefaultProperties(Map.of(
                    "spring.autoconfigure.exclude",
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"));
        }
        application.run(args);
    }

    private static boolean hasDataSource(String[] args) {
        return configured(System.getenv("SPRING_DATASOURCE_URL"))
                || configured(System.getProperty("spring.datasource.url"))
                || Arrays.stream(args).anyMatch(value -> value.startsWith("--spring.datasource.url=") && configured(value.substring(value.indexOf('=') + 1)));
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }
}
