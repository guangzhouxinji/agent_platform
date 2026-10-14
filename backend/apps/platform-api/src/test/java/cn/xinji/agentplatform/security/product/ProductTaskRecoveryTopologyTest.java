package cn.xinji.agentplatform.security.product;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Read-only recovery configuration is not proof of DM8 restoration or permission to reopen. */
class ProductTaskRecoveryTopologyTest {
    private ProductTaskRecoveryCheck.Connections config(String mode,String url,String schema,String user) {
        return new ProductTaskRecoveryCheck.Connections("jdbc:invalid:never-connect","BUSINESS_SVC","secret","BUSINESS",
                url,user,"different-secret",schema,"https://issuer",Path.of("unused"),1024,mode);
    }
    @Test void explicitSharedInstanceResolvesTheSameStoreWithoutMergingSchemasOrCredentials() {
        var settings=ProductTaskRecoveryCheck.authoritySettings(config("shared-instance","","AUTHORITY","AUTH_SVC"));
        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:invalid:never-connect");
        assertThat(settings.schema()).isEqualTo("AUTHORITY");assertThat(settings.username()).isEqualTo("AUTH_SVC");
        assertThat(settings.password()).isEqualTo("different-secret");
    }
    @Test void invalidTopologyAndCollapsedBoundariesAreRejectedBeforeAnyRecoveryRead() {
        assertThatThrownBy(()->ProductTaskRecoveryCheck.verify(config("independent","jdbc:invalid:never-connect","AUTHORITY","AUTH_SVC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductTaskRecoveryCheck.verify(config("shared-instance","","BUSINESS","AUTH_SVC")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductTaskRecoveryCheck.verify(config("shared-instance","","AUTHORITY","business_svc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductTaskRecoveryCheck.verify(config("anything","other","AUTHORITY","AUTH_SVC")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void existingRecoveryCallersStayIndependentByDefault() {
        var old=new ProductTaskRecoveryCheck.Connections("primary","BUSINESS_SVC","secret","BUSINESS",
                "authority","AUTH_SVC","different-secret","AUTHORITY","issuer",Path.of("unused"),1024);
        assertThat(old.topology()).isEqualTo("independent");
        assertThat(ProductTaskRecoveryCheck.authoritySettings(old).jdbcUrl()).isEqualTo("authority");
    }
}