package cn.xinji.agentplatform.adjudication.casework;

import cn.xinji.agentplatform.adjudication.casework.CaseWorkContract.Policy;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name="adjudication.v2.enabled",havingValue="true")
public class CaseWorkConfiguration {
    public CaseWorkConfiguration(@Value("${product.identity.enabled:false}")boolean identity){if(!identity)throw new IllegalStateException("Trusted product identity required");}
    @Bean CaseWorkCodec caseWorkCodec(ObjectMapper mapper){return new CaseWorkCodec(mapper);}
    @Bean CaseSourceAuthority caseSourceAuthority(ProductGrantWatermark grants,CaseWorkCodec codec){
        var independent=grants.independentDatabase();
        return new CaseSourceAuthority(independent,new TransactionTemplate(new DataSourceTransactionManager(independent.getDataSource())),codec,Clock.systemUTC());
    }
    @Bean CaseWorkRepository caseWorkRepository(JdbcTemplate db,PlatformTransactionManager manager,CaseWorkCodec codec){return new JdbcCaseWorkRepository(db,new TransactionTemplate(manager),codec,Clock.systemUTC());}
    @Bean CaseWorkCommands caseWorkCommands(CaseWorkRepository repository,CaseWorkCodec codec,@Value("${adjudication.v2.report-max-text-length:100000}")int max,@Value("${adjudication.v2.report-policy-file:}")String file)throws java.io.IOException{
        return new CaseWorkCommands(repository,codec,CaseReportPolicy.load(codec,file,max),Clock.systemUTC());
    }
    @Bean CaseWorkTransportFilter caseWorkTransport(ProductGrantWatermark grants,CaseSourceAuthority sources){return new CaseWorkTransportFilter(grants,sources);}
    @Bean @Order(3) SecurityFilterChain adjudicationSecurity(HttpSecurity http)throws Exception{
        return http.securityMatcher("/v2/adjudication/**").csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests->requests.requestMatchers("/v2/adjudication/source-uses","/v2/adjudication/source-uses/**","/v2/adjudication/grants","/v2/adjudication/grants/**").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(oauth->oauth.jwt(Customizer.withDefaults())).build();
    }
}
