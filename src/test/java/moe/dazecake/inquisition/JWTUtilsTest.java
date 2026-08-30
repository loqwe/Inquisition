package moe.dazecake.inquisition;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.utils.JWTUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JWTUtilsTest {
    @AfterEach void cleanup() { JWTUtils.clearRevocations(); System.clearProperty("inquisition.jwt.ttl"); }

    @Test void tokenContainsStandardClaimsAndTwoHourCap() {
        JWTUtils.SECRET = "test-secret";
        AccountEntity account = new AccountEntity().setId(7L).setAccount("u");
        String token = JWTUtils.generateTokenForUser(account);
        DecodedJWT jwt = JWT.decode(token);
        assertNotNull(jwt.getIssuer()); assertNotNull(jwt.getAudience());
        assertNotNull(jwt.getId()); assertNotNull(jwt.getIssuedAt());
        assertTrue(jwt.getExpiresAt().getTime() - System.currentTimeMillis() <= 7200000L);
        assertTrue(JWTUtils.verifyToken("Bearer " + token));
    }

    @Test void revokeAndSafeAccessors() {
        JWTUtils.SECRET = "test-secret";
        String token = JWTUtils.generateTokenForUser(new AccountEntity().setId(1L).setAccount("u"));
        assertTrue(JWTUtils.verifyToken(token)); JWTUtils.revoke(token); assertFalse(JWTUtils.verifyToken(token));
        assertNull(JWTUtils.getId("bad-token")); assertNull(JWTUtils.getType(null));
    }
}
