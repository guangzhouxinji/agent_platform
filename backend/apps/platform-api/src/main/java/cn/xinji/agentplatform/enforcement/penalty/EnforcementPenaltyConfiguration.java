package cn.xinji.agentplatform.enforcement.penalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name="enforcement.v2.enabled",havingValue="true")
public class EnforcementPenaltyConfiguration {
    @Bean @Primary Dm8PenaltyStore penaltyStore(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,
            @Value("${enforcement.v2.penalty.file-root:var/enforcement-penalty}")String root) {
        return new Dm8PenaltyStore(db,new TransactionTemplate(manager),json,Clock.systemUTC(),new PenaltyArtifacts(Path.of(root).toAbsolutePath()));
    }
    @Bean("penaltyCurrentStore") Dm8PenaltyStore current(JdbcTemplate db,PlatformTransactionManager manager,ObjectMapper json,
            @Value("${enforcement.v2.penalty.current-db-url:}")String url,
            @Value("${enforcement.v2.penalty.current-db-user:}")String user,
            @Value("${enforcement.v2.penalty.current-db-password:}")String password,
            @Value("${enforcement.v2.penalty.current-file-root:}")String currentRoot,
            @Value("${enforcement.v2.penalty.file-root:var/enforcement-penalty}")String root) {
        if(url.isBlank())return new Dm8PenaltyStore(db,new TransactionTemplate(manager),json,Clock.systemUTC(),new PenaltyArtifacts(Path.of(root).toAbsolutePath()));
        if(!url.startsWith("jdbc:dm://")||user.isBlank()||password.isBlank()||currentRoot.isBlank())throw new IllegalStateException("Independent current DM8/file configuration required");
        var data=new org.springframework.jdbc.datasource.DriverManagerDataSource(url,user,password);
        return new Dm8PenaltyStore(new JdbcTemplate(data),new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(data)),json,Clock.systemUTC(),new PenaltyArtifacts(Path.of(currentRoot).toAbsolutePath()));
    }
}
