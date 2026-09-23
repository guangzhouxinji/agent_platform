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
