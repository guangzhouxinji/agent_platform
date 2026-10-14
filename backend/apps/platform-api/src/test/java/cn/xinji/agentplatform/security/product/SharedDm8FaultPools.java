package cn.xinji.agentplatform.security.product;
import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceFactory;
import cn.xinji.agentplatform.persistence.authority.AuthorityDataSourceSettings;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Two dedicated real DM8 pools, one exact URL; restricted users never own schema DDL. */
final class SharedDm8FaultPools implements AutoCloseable {
    final HikariDataSource main=new HikariDataSource();
    final AuthorityDataSourceFactory factory=new AuthorityDataSourceFactory();
    final HikariDataSource auth;
    final JdbcTemplate primary,authority;
    final TransactionTemplate tx;
    SharedDm8FaultPools(String url,String mainSchema,String mainUser,String authSchema,String authUser,String secret) {
        main.setJdbcUrl(url);main.setSchema(mainSchema);main.setUsername(mainUser);main.setPassword(secret);
        main.setMaximumPoolSize(2);main.setMinimumIdle(0);main.setConnectionTimeout(5000);main.setPoolName("owned-coordination-main");
        try {
            auth=factory.open(AuthorityDataSourceSettings.resolve("shared-instance",url,mainSchema,mainUser,url,authSchema,authUser,secret));
            primary=new JdbcTemplate(main);authority=new JdbcTemplate(auth);
            tx=new TransactionTemplate(new DataSourceTransactionManager(main));
        } catch(RuntimeException|Error ex){main.close();factory.close();throw ex;}
    }
    @Override public void close(){factory.close();main.close();}
}
