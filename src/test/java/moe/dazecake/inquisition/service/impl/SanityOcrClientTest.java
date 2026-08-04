package moe.dazecake.inquisition.service.impl;

import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SanityOcrClientTest {

    @Test
    void downloadsAllowedImageAndCallsOcrSidecar() throws Exception {
        try (var imageServer = new MockWebServer(); var ocrServer = new MockWebServer()) {
            imageServer.enqueue(new MockResponse()
                    .setHeader("Content-Type", "image/png")
                    .setBody(new okio.Buffer().write(new byte[]{1, 2, 3, 4})));
            ocrServer.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"currentSanity\":1,\"maxSanity\":210,\"confidence\":0.92,\"votes\":3}"));
            imageServer.start();
            ocrServer.start();
            var imageUrl = imageServer.url("/image.png");
            var client = new SanityOcrClient(new OkHttpClient(), new Gson(),
                    ocrServer.url("/v1/sanity").toString(), Set.of(imageUrl.host()), 1024L);

            var result = client.recognize(imageUrl.toString());

            assertTrue(result.isPresent());
            assertEquals(1, result.get().getCurrentSanity());
            assertEquals(210, result.get().getMaxSanity());
            var userAgent = imageServer.takeRequest().getHeader("User-Agent");
            assertTrue(userAgent.startsWith("Mozilla/5.0"));
            assertTrue(userAgent.contains("Chrome/"));
            assertEquals("POST", ocrServer.takeRequest().getMethod());
        }
    }

    @Test
    void rejectsImageHostOutsideTheAllowList() {
        var client = new SanityOcrClient(new OkHttpClient(), new Gson(),
                "http://localhost:8000/v1/sanity", Set.of("inquisition-img.example"), 1024L);

        assertFalse(client.recognize("http://127.0.0.1/private.png").isPresent());
    }

    @Test
    void rejectsNonImageContent() throws Exception {
        try (var imageServer = new MockWebServer()) {
            imageServer.enqueue(new MockResponse().setHeader("Content-Type", "text/html").setBody("not an image"));
            imageServer.start();
            var imageUrl = imageServer.url("/image.png");
            var client = new SanityOcrClient(new OkHttpClient(), new Gson(),
                    "http://localhost:8000/v1/sanity", Set.of(imageUrl.host()), 1024L);

            assertFalse(client.recognize(imageUrl.toString()).isPresent());
        }
    }
}
