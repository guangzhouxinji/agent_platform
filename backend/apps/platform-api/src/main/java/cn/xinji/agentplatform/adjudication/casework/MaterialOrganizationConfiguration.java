package cn.xinji.agentplatform.adjudication.casework;
import java.nio.file.*;
import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.adjudication.materials.MaterialOrganizationModel;
import cn.xinji.agentplatform.adjudication.materials.adapter.DeepSeekMaterialOrganizationAdapter;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration
@ConditionalOnProperty(name="adjudication.v2.materials.enabled",havingValue="true")
public class MaterialOrganizationConfiguration {
    @Bean MaterialModelPolicy materialModelPolicy(ObjectMapper json,@Value("${adjudication.v2.materials.policy-file}")String file)throws Exception{return json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(Files.readString(Path.of(file)),MaterialModelPolicy.class);}
    @Bean MaterialRunVault materialRunVault(@Value("${adjudication.v2.materials.key-directory}")String directory){if(directory.isBlank())throw new IllegalStateException("MODEL_KEY_DIRECTORY_REQUIRED");return new MaterialRunVault(Path.of(directory),Clock.systemUTC());}
    @Bean JdbcMaterialRunStore materialRunStore(JdbcTemplate db,PlatformTransactionManager manager,MaterialRunVault vault,MaterialModelPolicy policy){var store=new JdbcMaterialRunStore(db,new TransactionTemplate(manager),vault,Clock.systemUTC());store.purgeExpired();store.initialize(policy);return store;}
    @Bean(destroyMethod="shutdownNow") java.util.concurrent.ScheduledExecutorService materialModelRetentionWorker(JdbcMaterialRunStore store,org.springframework.beans.factory.ObjectProvider<JdbcMaterialDecisionStore> decisions,PlatformTransactionManager manager) {
        var worker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"adjudication-model-retention");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(()->{try{decisions.ifAvailable(d->d.purgeExpired(new TransactionTemplate(manager)));store.purgeExpired();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(MaterialOrganizationConfiguration.class).error("MODEL_RETENTION_PURGE_FAILED: model branch remains closed until cleanup succeeds");}},60,60,java.util.concurrent.TimeUnit.SECONDS);
        return worker;
    }
    @Bean MaterialOrganizationModel materialOrganizationModel(@Value("${DEEPSEEK_API_KEY:}")String key,MaterialModelPolicy policy,
            @Value("${platform.model-gateway.deepseek.endpoint:}")String endpoint){return new DeepSeekMaterialOrganizationAdapter(
                    endpoint.isBlank()?cn.xinji.agentplatform.model.gateway.ModelProviderDefaults.deepSeekEndpoint():java.net.URI.create(endpoint),key,policy.modelVersion());}
    @Bean MaterialOrganizationService materialOrganizationService(CaseWorkRepository cases,JdbcMaterialRunStore runs,MaterialModelPolicy policy,MaterialOrganizationModel model,ObjectMapper json,PlatformTransactionManager manager,CaseSourceAuthority sources,ProductGrantWatermark grants){return new MaterialOrganizationService(cases,runs,policy,model,json,new TransactionTemplate(manager),sources,grants,Clock.systemUTC());}
}
