package cn.xinji.agentplatform.adjudication.casework;
import java.nio.file.*;
import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.xinji.agentplatform.adjudication.casework.persistence.CaseWorkRepository;
import cn.xinji.agentplatform.adjudication.materials.MaterialCandidateDecisions;
import cn.xinji.agentplatform.security.product.ProductGrantWatermark;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
@Configuration
@ConditionalOnProperty(name="adjudication.v2.materials.decisions.enabled",havingValue="true")
public class MaterialDecisionConfiguration {
    @Bean MaterialDecisionVault materialDecisionVault(@Value("${adjudication.v2.materials.decisions.key-directory}")String directory,@Value("${adjudication.v2.materials.key-directory}")String modelDirectory)throws Exception{
        if(directory.isBlank()||modelDirectory.isBlank())throw new IllegalStateException("MATERIAL_DECISION_KEY_DIRECTORY_REQUIRED");
        Path d=Path.of(directory).toAbsolutePath().normalize(),m=Path.of(modelDirectory).toAbsolutePath().normalize();
        if(d.startsWith(m)||m.startsWith(d))throw new IllegalStateException("MATERIAL_DECISION_KEY_DIRECTORY_NOT_INDEPENDENT");
        var vault=new MaterialDecisionVault(d,Clock.systemUTC());if(d.toRealPath().startsWith(m.toRealPath())||m.toRealPath().startsWith(d.toRealPath()))throw new IllegalStateException("MATERIAL_DECISION_KEY_DIRECTORY_NOT_INDEPENDENT");return vault;
    }
    @Bean JdbcMaterialDecisionStore materialDecisionStore(JdbcTemplate db,MaterialDecisionVault vault,CaseWorkCodec codec,PlatformTransactionManager manager){var store=new JdbcMaterialDecisionStore(db,vault,codec,Clock.systemUTC());store.purgeExpired(new TransactionTemplate(manager));return store;}
    @Bean MaterialDecisionService materialDecisionService(CaseWorkRepository cases,JdbcMaterialDecisionStore decisions,JdbcMaterialRunStore runs,MaterialOrganizationService organization,MaterialModelPolicy policy,JdbcTemplate db,CaseSourceAuthority sources,ProductGrantWatermark grants,CaseWorkCodec codec,ObjectMapper json,PlatformTransactionManager manager,@Value("${adjudication.v2.materials.decisions.text-limit:2000}")int text,@Value("${adjudication.v2.materials.decisions.reason-limit:2000}")int reason){return new MaterialDecisionService(cases,decisions,runs,organization,policy,db,sources,grants,codec,new MaterialCandidateDecisions(new MaterialCandidateDecisions.Limits(text,reason)),Clock.systemUTC(),json,new TransactionTemplate(manager));}
}
