package cn.xinji.agentplatform.modelconfig;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class ModelSecretCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    private ModelSecretCipher(SecretKeySpec key) { this.key = key; }

    static ModelSecretCipher fromEnvironment(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) throw new IllegalStateException("MODEL_CONFIG_MASTER_KEY is not configured");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encodedKey.trim()); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("MODEL_CONFIG_MASTER_KEY must be Base64", exception); }
        if (bytes.length != 32) throw new IllegalStateException("MODEL_CONFIG_MASTER_KEY must decode to 32 bytes");
        return new ModelSecretCipher(new SecretKeySpec(bytes, "AES"));
    }

    Encrypted encrypt(String secret) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new Encrypted(Base64.getEncoder().encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8))), Base64.getEncoder().encodeToString(nonce));
        } catch (Exception exception) { throw new IllegalStateException("Unable to encrypt model secret", exception); }
    }

    String decrypt(String encrypted, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(nonce)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("Unable to decrypt model secret", exception); }
    }

    record Encrypted(String value, String nonce) {}
}
