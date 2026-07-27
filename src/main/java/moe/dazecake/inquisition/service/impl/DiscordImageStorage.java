package moe.dazecake.inquisition.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import moe.dazecake.inquisition.utils.Result;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class DiscordImageStorage {
    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{17,20}");
    private static final Pattern UPLOAD_TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,}");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final boolean enabled;
    private final HttpUrl workerBaseUrl;
    private final String uploadToken;
    private final long maxImageBytes;

    @Autowired
    public DiscordImageStorage(
            @Value("${storage.discord.enable:false}") boolean enabled,
            @Value("${storage.discord.worker-base-url:}") String workerBaseUrl,
            @Value("${storage.discord.upload-token:}") String uploadToken,
            @Value("${storage.discord.max-image-bytes:8388608}") long maxImageBytes) {
        this(defaultHttpClient(), new Gson(), enabled, workerBaseUrl, uploadToken, maxImageBytes);
    }

    DiscordImageStorage(OkHttpClient httpClient, Gson gson, boolean enabled, String workerBaseUrl,
                        String uploadToken, long maxImageBytes) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.gson = Objects.requireNonNull(gson, "gson");
        this.enabled = enabled;
        this.maxImageBytes = maxImageBytes;

        if (!enabled) {
            this.workerBaseUrl = null;
            this.uploadToken = "";
            return;
        }
        if (maxImageBytes <= 0) {
            throw new IllegalStateException("storage.discord.max-image-bytes 必须大于 0");
        }
        this.workerBaseUrl = normalizeWorkerBaseUrl(workerBaseUrl);
        this.uploadToken = normalizeUploadToken(uploadToken);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Result<String> uploadImage(String base64Image) {
        if (!enabled) {
            return Result.failed("Discord Worker 图床未启用");
        }
        try {
            var image = decodeImage(base64Image);
            var fileName = "inquisition-" + UUID.randomUUID() + image.extension;
            var requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", fileName,
                            RequestBody.create(image.bytes, MediaType.get(image.contentType)))
                    .build();
            var uploadUrl = workerBaseUrl.newBuilder()
                    .addPathSegment("upload")
                    .build();
            var request = new Request.Builder()
                    .url(uploadUrl)
                    .header("Authorization", "Bearer " + uploadToken)
                    .header("User-Agent", "Inquisition/1.3.1")
                    .post(requestBody)
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() == 401 || response.code() == 403) {
                    return Result.failed("Discord Worker 图床鉴权失败");
                }
                if (!response.isSuccessful() || response.body() == null) {
                    return Result.failed("Discord Worker 图床上传失败: HTTP " + response.code());
                }
                var payload = parseJson(response.body().string(), "Discord Worker 上传响应无效");
                var messageId = stringValue(payload, "messageId");
                var attachmentId = stringValue(payload, "attachmentId");
                var returnedUrl = stringValue(payload, "url");
                if (!isSnowflake(messageId) || !isSnowflake(attachmentId)) {
                    return Result.failed("Discord Worker 图床上传失败: 响应缺少附件标识");
                }
                var stableUrl = stableUrl(messageId, attachmentId);
                if (returnedUrl == null || !stableUrl.equals(HttpUrl.parse(returnedUrl))) {
                    return Result.failed("Discord Worker 图床上传失败: 响应地址无效");
                }
                return Result.success(stableUrl.toString(), "上传成功");
            }
        } catch (IllegalArgumentException e) {
            return Result.paramError(e.getMessage());
        } catch (IOException | JsonParseException e) {
            return Result.failed("Discord Worker 图床上传失败，请检查 Worker 配置或服务状态");
        }
    }

    public String resolveAttachmentUrl(String messageId, String attachmentId) throws IOException {
        if (!enabled) {
            throw new FileNotFoundException("Discord Worker 图床未启用");
        }
        requireSnowflake(messageId, "messageId");
        requireSnowflake(attachmentId, "attachmentId");
        return stableUrl(messageId, attachmentId).toString();
    }

    private DecodedImage decodeImage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("图片内容不能为空");
        }
        var encoded = value.trim();
        var contentType = "image/png";
        if (encoded.regionMatches(true, 0, "data:", 0, 5)) {
            var comma = encoded.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("图片 Base64 格式无效");
            }
            var metadata = encoded.substring(5, comma).toLowerCase(Locale.ROOT);
            if (!metadata.contains(";base64")) {
                throw new IllegalArgumentException("图片必须使用 Base64 编码");
            }
            var separator = metadata.indexOf(';');
            contentType = separator < 0 ? metadata : metadata.substring(0, separator);
            encoded = encoded.substring(comma + 1);
        }
        var extension = extensionFor(contentType);
        var maximumEncodedLength = ((maxImageBytes + 2L) / 3L) * 4L + 1024L;
        if (encoded.length() > maximumEncodedLength) {
            throw new IllegalArgumentException("图片超过 Discord Worker 图床大小限制");
        }
        final byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片 Base64 格式无效");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("图片内容不能为空");
        }
        if (bytes.length > maxImageBytes) {
            throw new IllegalArgumentException("图片超过 Discord Worker 图床大小限制");
        }
        return new DecodedImage(bytes, contentType, extension);
    }

    private HttpUrl stableUrl(String messageId, String attachmentId) {
        return workerBaseUrl.newBuilder()
                .addPathSegment("file")
                .addPathSegment(messageId)
                .addPathSegment(attachmentId)
                .build();
    }

    private static String extensionFor(String contentType) {
        switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png":
                return ".png";
            case "image/jpeg":
            case "image/jpg":
                return ".jpg";
            case "image/webp":
                return ".webp";
            case "image/gif":
                return ".gif";
            default:
                throw new IllegalArgumentException("不支持的图片类型: " + contentType);
        }
    }

    private static JsonObject parseJson(String value, String message) {
        var parsed = com.google.gson.JsonParser.parseString(value);
        if (!parsed.isJsonObject()) {
            throw new JsonParseException(message);
        }
        return parsed.getAsJsonObject();
    }

    private static String stringValue(JsonObject object, String field) {
        if (!object.has(field)) {
            return null;
        }
        var value = object.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        var primitive = value.getAsJsonPrimitive();
        return primitive.isString() || primitive.isNumber() ? primitive.getAsString() : null;
    }

    private static void requireSnowflake(String value, String field) {
        if (!isSnowflake(value)) {
            throw new IllegalArgumentException(field + " 格式无效");
        }
    }

    private static boolean isSnowflake(String value) {
        return value != null && SNOWFLAKE.matcher(value).matches();
    }

    private static HttpUrl normalizeWorkerBaseUrl(String value) {
        var parsed = value == null ? null : HttpUrl.parse(value.trim());
        if (parsed == null || parsed.host().isBlank()) {
            throw new IllegalStateException("storage.discord.worker-base-url 配置无效");
        }
        var localHttp = "http".equalsIgnoreCase(parsed.scheme())
                && ("127.0.0.1".equals(parsed.host()) || "localhost".equalsIgnoreCase(parsed.host())
                || "::1".equals(parsed.host()));
        if (!"https".equalsIgnoreCase(parsed.scheme()) && !localHttp) {
            throw new IllegalStateException("storage.discord.worker-base-url 必须使用 HTTPS");
        }
        if (!parsed.username().isEmpty() || !parsed.password().isEmpty()
                || parsed.querySize() > 0 || parsed.fragment() != null
                || !"/".equals(parsed.encodedPath())) {
            throw new IllegalStateException("storage.discord.worker-base-url 只能配置 Worker 根地址");
        }
        return parsed;
    }

    private static String normalizeUploadToken(String value) {
        var normalized = value == null ? "" : value.trim();
        if (!UPLOAD_TOKEN.matcher(normalized).matches()) {
            throw new IllegalStateException("storage.discord.upload-token 配置无效");
        }
        return normalized;
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private static final class DecodedImage {
        private final byte[] bytes;
        private final String contentType;
        private final String extension;

        private DecodedImage(byte[] bytes, String contentType, String extension) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.extension = extension;
        }
    }
}
