package com.yeodam.yeodambe.user.migration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name="migration.auth.enabled", havingValue="true")
public class MigrationAuthCrypto {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public MigrationAuthCrypto(@Value("${migration.auth.key-file}") String path) throws Exception {
        byte[] bytes = HexFormat.of().parseHex(Files.readString(Path.of(path)).trim());
        if (bytes.length != 32) throw new IllegalArgumentException("Auth key must be AES256");
        key = new SecretKeySpec(bytes, "AES");
    }
    public String encrypt(String text, String topic) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(topic.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Auth encryption failed"); }
    }
    public String decrypt(String wire, String topic) {
        try {
            String[] fields = wire.split("\\.", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(fields[0])));
            cipher.updateAAD(topic.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(fields[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("Auth ciphertext rejected"); }
    }
}
