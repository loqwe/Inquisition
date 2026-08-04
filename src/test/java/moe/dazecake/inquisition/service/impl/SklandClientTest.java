package moe.dazecake.inquisition.service.impl;

import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import moe.dazecake.inquisition.model.entity.SklandCredentialEntity;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SklandClientTest {

    @Test
    void parsesSanityAndLastOnlineFromPlayerInfo() {
        var root = JsonParser.parseString("{\"data\":{\"status\":{\"lastOnlineTs\":1784433600,"
                + "\"ap\":{\"current\":20,\"max\":135,\"completeRecoveryTime\":1784475000}}}}").getAsJsonObject();

        var status = SklandPlayerStatus.fromJson(root);

        assertEquals(20, status.getCurrentSanity());
        assertEquals(135, status.getMaxSanity());
        assertNotNull(status.getLastOnlineAt());
    }

    @Test
    void transparentlyDecompressesGzipPlayerInfo() throws Exception {
        var json = "{\"code\":0,\"data\":{\"status\":{\"lastOnlineTs\":1784433600,"
                + "\"ap\":{\"current\":20,\"max\":135,\"completeRecoveryTime\":1784475000}}}}";
        var compressed = gzip(json);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/game/player/info", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, compressed.length);
            try (var body = exchange.getResponseBody()) {
                body.write(compressed);
            }
        });
        server.start();
        try {
            var client = new SklandClient(new OkHttpClient(), new Gson(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
            var status = client.queryPlayerInfo(new SklandCredentialEntity()
                    .setCred("cred").setCredToken("token").setUid("uid"));

            assertEquals(20, status.getCurrentSanity());
            assertEquals(135, status.getMaxSanity());
        } finally {
            server.stop(0);
        }
    }

    private byte[] gzip(String value) throws Exception {
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }
}
