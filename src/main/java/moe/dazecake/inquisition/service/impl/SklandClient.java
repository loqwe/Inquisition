package moe.dazecake.inquisition.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SklandClient {
    public static final String DEFAULT_BASE_URL = "https://zonai.skland.com/api/v1";
    private static final String USER_AGENT = "Skland/1.32.1 (com.hypergryph.skland; build:103201004; Android 33; ) Okhttp/4.11.0";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final String baseUrl;

    public SklandClient() {
        this(new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .writeTimeout(8, TimeUnit.SECONDS)
                        .callTimeout(10, TimeUnit.SECONDS)
                        .build(),
                new Gson(), DEFAULT_BASE_URL);
    }

    SklandClient(OkHttpClient httpClient, Gson gson, String baseUrl) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.baseUrl = baseUrl;
    }

    public SklandPlayerStatus queryPlayerInfo(SklandCredentialEntity credential) throws IOException {
        if (credential == null || isBlank(credential.getCred()) || isBlank(credential.getCredToken())
                || isBlank(credential.getUid())) {
            throw new IOException("森空岛凭据不完整");
        }
        var url = baseUrl + "/game/player/info?uid=" + credential.getUid();
        var timestamp = System.currentTimeMillis() / 1000L;
        var signed = SklandSigner.sign(credential.getCredToken(), url, timestamp);
        var requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Connection", "close")
                .header("cred", credential.getCred())
                .header("sign", signed.signature());
        signed.headers().forEach(requestBuilder::header);
        try (var response = httpClient.newCall(requestBuilder.get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("森空岛请求失败: HTTP " + response.code());
            }
            var root = gson.fromJson(response.body().string(), JsonObject.class);
            var code = root.has("code") ? root.get("code").getAsInt() : 0;
            if (code != 0) {
                throw new IOException("森空岛请求失败: code=" + code + ", message="
                        + (root.has("message") ? root.get("message").getAsString() : "unknown"));
            }
            return SklandPlayerStatus.fromJson(root);
        }
    }

    public String refreshCredToken(String cred) throws IOException {
        var request = new Request.Builder()
                .url(baseUrl + "/auth/refresh")
                .header("User-Agent", USER_AGENT)
                .header("cred", cred)
                .get()
                .build();
        try (var response = httpClient.newCall(request).execute()) {
            var root = readJson(response, "森空岛凭据刷新失败");
            var status = root.has("status") ? root.get("status").getAsInt() : 0;
            if (status != 0) {
                throw new IOException("森空岛凭据刷新失败: status=" + status);
            }
            return root.getAsJsonObject("data").get("token").getAsString();
        }
    }

    public GeneratedCredential generateCredential(String accessToken) throws IOException {
        var grantBody = "{\"appCode\":\"4ca99fa6b56cc2ba\",\"token\":"
                + gson.toJson(accessToken) + ",\"type\":0}";
        var grantRoot = postJson("https://as.hypergryph.com/user/oauth2/v2/grant", grantBody);
        var grantStatus = grantRoot.has("status") ? grantRoot.get("status").getAsInt() : 0;
        if (grantStatus != 0) {
            throw new IOException("森空岛授权失败: status=" + grantStatus);
        }
        var code = grantRoot.getAsJsonObject("data").get("code").getAsString();
        var credRoot = postJson("https://zonai.skland.com/api/v1/user/auth/generate_cred_by_code",
                "{\"code\":" + gson.toJson(code) + ",\"kind\":1}");
        var credStatus = credRoot.has("status") ? credRoot.get("status").getAsInt() : 0;
        if (credStatus != 0) {
            throw new IOException("森空岛凭据生成失败: status=" + credStatus);
        }
        var data = credRoot.getAsJsonObject("data");
        return new GeneratedCredential(
                data.get("cred").getAsString(),
                data.get("token").getAsString(),
                data.has("userId") && !data.get("userId").isJsonNull()
                        ? data.get("userId").getAsString() : null);
    }

    public List<Binding> getBindings(SklandCredentialEntity credential) throws IOException {
        var url = baseUrl + "/game/player/binding";
        var signed = SklandSigner.sign(credential.getCredToken(), url,
                System.currentTimeMillis() / 1000L);
        var requestBuilder = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Connection", "close")
                .header("cred", credential.getCred())
                .header("sign", signed.signature());
        signed.headers().forEach(requestBuilder::header);
        try (var response = httpClient.newCall(requestBuilder.get().build()).execute()) {
            var root = readJson(response, "森空岛绑定角色查询失败");
            var code = root.has("code") ? root.get("code").getAsInt() : 0;
            if (code != 0) {
                throw new IOException("森空岛绑定角色查询失败: code=" + code);
            }
            var result = new ArrayList<Binding>();
            var list = root.getAsJsonObject("data").getAsJsonArray("list");
            if (list == null) {
                return result;
            }
            list.forEach(item -> {
                var app = item.getAsJsonObject();
                var bindingList = app.getAsJsonArray("bindingList");
                if (bindingList == null) {
                    return;
                }
                bindingList.forEach(bindingItem -> {
                    var binding = bindingItem.getAsJsonObject();
                    result.add(new Binding(
                            stringValue(binding, "uid"),
                            stringValue(binding, "channelMasterId"),
                            stringValue(binding, "gameName"),
                            booleanValue(binding, "isDefault")));
                });
            });
            return result;
        }
    }

    private JsonObject postJson(String url, String json) throws IOException {
        var request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", JSON.toString())
                .post(RequestBody.create(json, JSON))
                .build();
        try (var response = httpClient.newCall(request).execute()) {
            return readJson(response, "森空岛请求失败");
        }
    }

    private JsonObject readJson(okhttp3.Response response, String prefix) throws IOException {
        if (!response.isSuccessful() || response.body() == null) {
            throw new IOException(prefix + ": HTTP " + response.code());
        }
        return gson.fromJson(response.body().string(), JsonObject.class);
    }

    private static String stringValue(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() ? object.get(field).getAsString() : null;
    }

    private static boolean booleanValue(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull() && object.get(field).getAsBoolean();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class GeneratedCredential {
        private final String cred;
        private final String credToken;
        private final String userId;

        public GeneratedCredential(String cred, String credToken, String userId) {
            this.cred = cred;
            this.credToken = credToken;
            this.userId = userId;
        }

        public String getCred() {
            return cred;
        }

        public String getCredToken() {
            return credToken;
        }

        public String getUserId() {
            return userId;
        }
    }

    public static final class Binding {
        private final String uid;
        private final String channelMasterId;
        private final String gameName;
        private final boolean defaultRole;

        public Binding(String uid, String channelMasterId, String gameName, boolean defaultRole) {
            this.uid = uid;
            this.channelMasterId = channelMasterId;
            this.gameName = gameName;
            this.defaultRole = defaultRole;
        }

        public String getUid() {
            return uid;
        }

        public String getChannelMasterId() {
            return channelMasterId;
        }

        public String getGameName() {
            return gameName;
        }

        public boolean isDefaultRole() {
            return defaultRole;
        }
    }
}
