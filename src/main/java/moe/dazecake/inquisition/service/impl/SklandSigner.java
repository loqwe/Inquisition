package moe.dazecake.inquisition.service.impl;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SklandSigner {
    private SklandSigner() {
    }

    public static SignResult sign(String credToken, String url, long timestamp) {
        try {
            var uri = URI.create(url);
            var query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            var timestampText = Long.toString(timestamp);
            var headerJson = "{\"platform\":\"\",\"timestamp\":\"" + timestampText
                    + "\",\"dId\":\"\",\"vName\":\"\"}";
            var secret = uri.getRawPath() + query + timestampText + headerJson;

            var hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(credToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var hmacHex = toHex(hmac.doFinal(secret.getBytes(StandardCharsets.UTF_8)));

            var md5 = MessageDigest.getInstance("MD5");
            var signature = toHex(md5.digest(hmacHex.getBytes(StandardCharsets.UTF_8)));

            var headers = new LinkedHashMap<String, String>();
            headers.put("platform", "");
            headers.put("timestamp", timestampText);
            headers.put("dId", "");
            headers.put("vName", "");
            return new SignResult(signature, headers);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign Skland request", e);
        }
    }

    private static String toHex(byte[] bytes) {
        var result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    public static final class SignResult {
        private final String signature;
        private final Map<String, String> headers;

        private SignResult(String signature, Map<String, String> headers) {
            this.signature = signature;
            this.headers = Collections.unmodifiableMap(headers);
        }

        public String signature() {
            return signature;
        }

        public Map<String, String> headers() {
            return headers;
        }
    }
}
