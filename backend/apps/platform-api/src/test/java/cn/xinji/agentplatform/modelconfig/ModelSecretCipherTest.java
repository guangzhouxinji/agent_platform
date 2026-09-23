package cn.xinji.agentplatform.modelconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class ModelSecretCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void encryptsWithUniqueNoncesAndDecryptsOnlyWithTheSameKey() {
        ModelSecretCipher cipher = ModelSecretCipher.fromEnvironment(KEY);
        var first = cipher.encrypt("secret-value");
        var second = cipher.encrypt("secret-value");

        assertThat(first.value()).doesNotContain("secret-value").isNotEqualTo(second.value());
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(cipher.decrypt(first.value(), first.nonce())).isEqualTo("secret-value");

        String otherKey = Base64.getEncoder().encodeToString(new byte[] {
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 });
        assertThatThrownBy(() -> ModelSecretCipher.fromEnvironment(otherKey).decrypt(first.value(), first.nonce()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unable to decrypt model secret");
    }

    @Test void rejectsMissingMalformedAndWrongLengthMasterKeys() {
        assertThatThrownBy(() -> ModelSecretCipher.fromEnvironment(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
        assertThatThrownBy(() -> ModelSecretCipher.fromEnvironment("not-base64"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
        assertThatThrownBy(() -> ModelSecretCipher.fromEnvironment(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
