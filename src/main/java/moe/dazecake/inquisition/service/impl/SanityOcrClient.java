package moe.dazecake.inquisition.service.impl;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SanityOcrClient {
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36";
    private static final long MAX_JSON_BYTES = 64 * 1024;

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final String serviceUrl;
    private final Set<String> allowedImageHosts;
    private final long maxImageBytes;

    @Autowired
    public SanityOcrClient(
            @Value("${inquisition.ocr.service-url:}") String serviceUrl,
            @Value("${inquisition.ocr.allowed-image-hosts:}") String allowedImageHosts,
            @Value("${inquisition.ocr.max-image-bytes:8388608}") long maxImageBytes) {
        this(defaultHttpClient(), new Gson(), serviceUrl, parseHosts(allowedImageHosts), maxImageBytes);
    }

    SanityOcrClient(OkHttpClient httpClient, Gson gson, String serviceUrl,
                    Set<String> allowedImageHosts, long maxImageBytes) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.allowedImageHosts = allowedImageHosts == null ? Collections.emptySet() : allowedImageHosts;
        this.maxImageBytes = maxImageBytes;
    }

    boolean isConfigured() {
        var endpoint = HttpUrl.parse(serviceUrl);
        return endpoint != null
                && ("http".equals(endpoint.scheme()) || "https".equals(endpoint.scheme()))
                && !allowedImageHosts.isEmpty()
                && maxImageBytes > 0;
    }

    public Optional<SanityOcrResult> recognize(String imageUrl) {
        if (serviceUrl.isBlank() || imageUrl == null || imageUrl.isBlank()) {
            return Optional.empty();
        }
        var parsedImageUrl = HttpUrl.parse(imageUrl);
        if (!isAllowedImageUrl(parsedImageUrl)) {
            log.warn("Skip sanity OCR image from a non-allowlisted host");
            return Optional.empty();
        }
        try {
            var image = downloadImage(parsedImageUrl);
            if (image == null) {
                return Optional.empty();
            }
            return callSidecar(image.bytes, image.contentType);
        } catch (IOException | RuntimeException exception) {
            log.warn("Sanity OCR request failed: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private DownloadedImage downloadImage(HttpUrl imageUrl) throws IOException {
        var request = new Request.Builder()
                .url(imageUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            var contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/")) {
                return null;
            }
            var contentLength = response.body().contentLength();
            if (contentLength > maxImageBytes) {
                return null;
            }
            var bytes = readLimited(response.body(), maxImageBytes);
            return bytes == null ? null : new DownloadedImage(bytes, contentType);
        }
    }

    private Optional<SanityOcrResult> callSidecar(byte[] image, String contentType) throws IOException {
        var mediaType = MediaType.parse(contentType);
        if (mediaType == null) {
            mediaType = MediaType.parse("application/octet-stream");
        }
        var request = new Request.Builder()
                .url(serviceUrl)
                .post(RequestBody.create(image, mediaType))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null
                    || response.body().contentLength() > MAX_JSON_BYTES) {
                return Optional.empty();
            }
            var result = gson.fromJson(response.body().charStream(), SanityOcrResult.class);
            if (!isValid(result)) {
                return Optional.empty();
            }
            return Optional.of(result);
        }
    }

    private boolean isAllowedImageUrl(HttpUrl url) {
        return url != null
                && ("http".equals(url.scheme()) || "https".equals(url.scheme()))
                && allowedImageHosts.contains(url.host().toLowerCase(Locale.ROOT));
    }

    private static boolean isValid(SanityOcrResult result) {
        return result != null
                && result.getCurrentSanity() >= 0
                && result.getCurrentSanity() <= result.getMaxSanity()
                && result.getMaxSanity() > 0
                && result.getMaxSanity() <= 999
                && result.getConfidence() >= 0.0d
                && result.getConfidence() <= 1.0d
                && result.getVotes() >= 2;
    }

    private static byte[] readLimited(ResponseBody body, long limit) throws IOException {
        try (InputStream input = body.byteStream(); var output = new ByteArrayOutputStream()) {
            var buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > limit) {
                    return null;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Set<String> parseHosts(String configuredHosts) {
        if (configuredHosts == null || configuredHosts.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(configuredHosts.split(","))
                .map(String::trim)
                .filter(host -> !host.isBlank())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(15))
                .writeTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(20))
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private static final class DownloadedImage {
        private final byte[] bytes;
        private final String contentType;

        private DownloadedImage(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType;
        }
    }
}
