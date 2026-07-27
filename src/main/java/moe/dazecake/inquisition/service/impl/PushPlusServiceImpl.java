package moe.dazecake.inquisition.service.impl;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PushPlusServiceImpl {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new Gson();
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build();

    public void push(String token, String title, String content) {
        try {
            var payload = new HashMap<String, String>();
            payload.put("token", token);
            payload.put("title", title);
            payload.put("content", content);
            payload.put("template", "markdown");
            var request = new Request.Builder()
                    .url("https://www.pushplus.plus/send")
                    .post(RequestBody.create(GSON.toJson(payload), JSON))
                    .build();
            try (var response = HTTP_CLIENT.newCall(request).execute()) {
                log.info("【审判庭】 PushPlus 状态: {}", response.code());
            }
        } catch (Exception e) {
            log.warn("【审判庭】 PushPlus 推送失败", e);
        }
    }
}
