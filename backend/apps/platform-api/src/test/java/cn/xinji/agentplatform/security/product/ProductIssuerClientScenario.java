package cn.xinji.agentplatform.security.product;

import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.transaction.support.TransactionTemplate;

final class ProductIssuerClientScenario {
    private ProductIssuerClientScenario() { }

    static void register(JdbcTemplate db, RegisteredClientRepository clients) {
        new ProductClients(db, clients,
                new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource())),
                Clock.systemUTC()).register(new ProductClients.Input("internal-client-check",
                "Issuer contract client", "https://client.example.org/callback"),
                "issuer-client-registration", "isolated-maintainer-probe");
    }
}
