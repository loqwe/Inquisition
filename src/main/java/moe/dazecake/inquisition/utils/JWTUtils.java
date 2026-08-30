package moe.dazecake.inquisition.utils;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import moe.dazecake.inquisition.model.entity.AccountEntity;
import moe.dazecake.inquisition.model.entity.AdminEntity;
import moe.dazecake.inquisition.model.entity.ProUserEntity;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JWTUtils {
    public static String SECRET;
    private static final long DEFAULT_EXPIRATION = 1000L * 60 * 60 * 2;
    private static final String ISSUER = "inquisition";
    private static final ConcurrentHashMap<String, Long> REVOKED = new ConcurrentHashMap<>();

    private static JWTCreator.Builder base(String type, long id) {
        long ttl = expiration();
        return JWT.create().withIssuer(ISSUER).withAudience(type).withIssuedAt(new Date())
                .withJWTId(UUID.randomUUID().toString()).withClaim("id", id).withClaim("type", type)
                .withExpiresAt(new Date(System.currentTimeMillis() + ttl));
    }

    private static long expiration() {
        try { return Math.min(Long.parseLong(System.getProperty("inquisition.jwt.ttl", "7200000")), DEFAULT_EXPIRATION); }
        catch (Exception ignored) { return DEFAULT_EXPIRATION; }
    }
    public static void configureExpirationMillis(long ttlMillis) {
        if (ttlMillis > 0) System.setProperty("inquisition.jwt.ttl", Long.toString(ttlMillis));
    }

    public static String generateTokenForAdmin(AdminEntity e) {
        return base("admin", e.getId()).withClaim("username", e.getUsername())
                .withClaim("permission", e.getPermission()).sign(Algorithm.HMAC256(SECRET));
    }

    public static String generateTokenForUser(AccountEntity e) {
        return base("user", e.getId()).withClaim("account", e.getAccount())
                .sign(Algorithm.HMAC256(SECRET));
    }

    public static String generateTokenForProUser(ProUserEntity e) {
        return base("proUser", e.getId()).withClaim("username", e.getUsername())
                .sign(Algorithm.HMAC256(SECRET));
    }

    public static boolean verifyToken(String token) {
        try {
            if (SECRET == null || SECRET.isEmpty() || token == null || token.isEmpty()) return false;
            DecodedJWT jwt = JWT.require(Algorithm.HMAC256(SECRET)).withIssuer(ISSUER)
                    .build().verify(stripBearer(token));
            String type = jwt.getClaim("type").asString();
            if (type == null || jwt.getAudience() == null || !jwt.getAudience().contains(type)) return false;
            Long revokedUntil = REVOKED.get(jwt.getId());
            if (revokedUntil != null) {
                if (revokedUntil > System.currentTimeMillis()) return false;
                REVOKED.remove(jwt.getId(), revokedUntil);
            }
            return jwt.getExpiresAt() != null && jwt.getExpiresAt().after(new Date());
        } catch (Exception e) { return false; }
    }

    public static void revokeToken(String token) {
        try { DecodedJWT jwt = JWT.decode(stripBearer(token)); if (jwt.getId() != null)
            REVOKED.put(jwt.getId(), jwt.getExpiresAt() == null ? Long.MAX_VALUE : jwt.getExpiresAt().getTime()); }
        catch (Exception ignored) { }
    }
    public static void revoke(String token) { revokeToken(token); }
    public static void clearRevocations() { REVOKED.clear(); }

    private static String stripBearer(String token) {
        return token != null && token.regionMatches(true, 0, "Bearer ", 0, 7) ? token.substring(7) : token;
    }
    private static DecodedJWT decode(String token) { return JWT.decode(stripBearer(token)); }
    public static Long getId(String token) { try { return decode(token).getClaim("id").asLong(); } catch (Exception e) { return null; } }
    public static String getAccount(String token) { try { return decode(token).getClaim("account").asString(); } catch (Exception e) { return null; } }
    public static String getType(String token) { try { return decode(token).getClaim("type").asString(); } catch (Exception e) { return null; } }
}
