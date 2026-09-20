package moe.dazecake.inquisition.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class EndfieldCredentialService {
    private static final byte[] INSECURE_DEFAULT_KEY = new byte[32];
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder verifier = new BCryptPasswordEncoder();
    private final byte[] key;

    public EndfieldCredentialService(@Value("${endfield.credential-key:}") String encodedKey) {
        byte[] decoded;
        try {
            decoded = encodedKey == null ? new byte[0] : Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("endfield.credential-key must be valid Base64", e);
        }
        if (decoded.length != 32 || MessageDigest.isEqual(decoded, INSECURE_DEFAULT_KEY)) {
            throw new IllegalStateException("endfield.credential-key must be a non-default 32-byte key");
        }
        key = decoded;
    }

    public String hash(String password) { return verifier.encode(password); }
    public boolean matches(String password, String hash) { return hash != null && verifier.matches(password, hash); }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) { throw new IllegalStateException("credential encryption failed", e); }
    }

    public String decrypt(String encoded) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, packed, 0, 12));
            return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalArgumentException("credential decryption failed", e); }
    }
}
