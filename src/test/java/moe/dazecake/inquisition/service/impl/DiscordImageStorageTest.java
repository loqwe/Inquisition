package moe.dazecake.inquisition.service.impl;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import moe.dazecake.inquisition.controller.DiscordImageController;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiscordImageStorageTest {
    private static final String UPLOAD_TOKEN = "upload-secret-123456789";
    private static final String PNG_DATA_URL = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private static final String MESSAGE_ID = "111111111111111111";
    private static final String ATTACHMENT_ID = "222222222222222222";

    @Test
    void springCreatesEnabledStorageWithConfiguredConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "storage.discord.enable=true",
                    "storage.discord.worker-base-url=https://img.example",
                    "storage.discord.upload-token=" + UPLOAD_TOKEN,
                    "storage.discord.max-image-bytes=8388608")
                    .applyTo(context);
            context.register(DiscordImageStorage.class);

            context.refresh();

            assertTrue(context.getBean(DiscordImageStorage.class).isEnabled());
        }
    }

    @Test
    void imageServiceUploadsThroughWorkerAndReturnsWorkerUrl() throws Exception {
        var authorization = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var requestMethod = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            var response = ("{\"url\":\"" + workerBaseUrl(server) + "/file/" + MESSAGE_ID + "/"
                    + ATTACHMENT_ID + "\",\"messageId\":\"" + MESSAGE_ID
                    + "\",\"attachmentId\":\"" + ATTACHMENT_ID + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            var imageService = new ImageServiceImpl();
            ReflectionTestUtils.setField(imageService, "discordImageStorage", storage(server));

            var result = imageService.uploadImage(PNG_DATA_URL);

            assertEquals(workerBaseUrl(server) + "/file/" + MESSAGE_ID + "/" + ATTACHMENT_ID,
                    result.getData());
            assertEquals("POST", requestMethod.get());
            assertEquals("Bearer " + UPLOAD_TOKEN, authorization.get());
            assertTrue(requestBody.get().contains("name=\"image\""));
            assertTrue(requestBody.get().contains("filename=\"inquisition-"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUploadUrlOutsideConfiguredWorker() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            var response = ("{\"url\":\"https://attacker.example/file/" + MESSAGE_ID + "/"
                    + ATTACHMENT_ID + "\",\"messageId\":\"" + MESSAGE_ID
                    + "\",\"attachmentId\":\"" + ATTACHMENT_ID + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, response.length);
            try (var body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            var result = storage(server).uploadImage(PNG_DATA_URL);

            assertNull(result.getData());
            assertTrue(result.getCode() != 200);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryNonIdempotentUpload() throws Exception {
        var requestCount = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var result = storage(server).uploadImage(PNG_DATA_URL);

            assertNull(result.getData());
            assertEquals(1, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidUploadTokenWhenEnabled() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            assertThrows(IllegalStateException.class, () -> new DiscordImageStorage(
                    new OkHttpClient(),
                    new Gson(),
                    true,
                    workerBaseUrl(server),
                    "short",
                    8 * 1024 * 1024L));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedImageBeforeCallingWorker() throws Exception {
        var requestCount = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            var storage = new DiscordImageStorage(
                    new OkHttpClient(),
                    new Gson(),
                    true,
                    workerBaseUrl(server),
                    UPLOAD_TOKEN,
                    16);

            var result = storage.uploadImage(PNG_DATA_URL);

            assertNull(result.getData());
            assertEquals(0, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyMediaRouteRedirectsToWorkerStableUrl() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        try {
            var controller = new DiscordImageController(storage(server));
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();

            mvc.perform(get("/media/discord/" + MESSAGE_ID + "/" + ATTACHMENT_ID))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location",
                            workerBaseUrl(server) + "/file/" + MESSAGE_ID + "/" + ATTACHMENT_ID))
                    .andExpect(header().string("Cache-Control", "no-store"));
        } finally {
            server.stop(0);
        }
    }

    private DiscordImageStorage storage(HttpServer server) {
        return new DiscordImageStorage(
                new OkHttpClient(),
                new Gson(),
                true,
                workerBaseUrl(server),
                UPLOAD_TOKEN,
                8 * 1024 * 1024L);
    }

    private static String workerBaseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
