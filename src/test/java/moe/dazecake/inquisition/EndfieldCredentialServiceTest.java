package moe.dazecake.inquisition;

import moe.dazecake.inquisition.security.EndfieldCredentialService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EndfieldCredentialServiceTest {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void encryptsWithAuthenticatedRandomIvAndBcryptVerifier() {
        EndfieldCredentialService service = new EndfieldCredentialService(KEY);
        String a = service.encrypt("终末地-password");
        String b = service.encrypt("终末地-password");
        assertNotEquals(a, b);
        assertEquals("终末地-password", service.decrypt(a));
        String hash = service.hash("终末地-password");
        assertTrue(service.matches("终末地-password", hash));
        assertFalse(service.matches("wrong", hash));
    }

    @Test
    void rejectsTamperedCiphertext() {
        EndfieldCredentialService service = new EndfieldCredentialService(KEY);
        String encoded = service.encrypt("secret");
        char last = encoded.charAt(encoded.length() - 1);
        String tampered = encoded.substring(0, encoded.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThrows(IllegalArgumentException.class, () -> service.decrypt(tampered));
    }

    @Test
    void rejectsMissingAndKnownDefaultKeys() {
        assertThrows(IllegalStateException.class, () -> new EndfieldCredentialService(""));
        assertThrows(IllegalStateException.class, () -> new EndfieldCredentialService(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));
    }
}
