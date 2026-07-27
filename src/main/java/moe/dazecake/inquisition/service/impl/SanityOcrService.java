package moe.dazecake.inquisition.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SanityOcrService {
    private final SanityOcrClient client;
    private final AccountRuntimeService accountRuntimeService;
    private final Executor executor;
    private final boolean enabled;
    private final double minimumConfidence;

    @Autowired
    public SanityOcrService(
            SanityOcrClient client,
            AccountRuntimeService accountRuntimeService,
            @Value("${inquisition.ocr.enabled:false}") boolean enabled,
            @Value("${inquisition.ocr.minimum-confidence:0.80}") double minimumConfidence) {
        this(client, accountRuntimeService, createExecutor(), enabled, minimumConfidence);
    }

    SanityOcrService(SanityOcrClient client, AccountRuntimeService accountRuntimeService,
                     Executor executor, boolean enabled, double minimumConfidence) {
        if (minimumConfidence < 0.0d || minimumConfidence > 1.0d) {
            throw new IllegalStateException("inquisition.ocr.minimum-confidence must be between 0 and 1");
        }
        if (enabled && !client.isConfigured()) {
            throw new IllegalStateException("OCR is enabled but its endpoint or image host allowlist is missing");
        }
        this.client = client;
        this.accountRuntimeService = accountRuntimeService;
        this.executor = executor;
        this.enabled = enabled;
        this.minimumConfidence = minimumConfidence;
    }

    public void submit(Long accountId, String imageUrl, LocalDateTime observedAt) {
        if (!enabled || accountId == null || observedAt == null || imageUrl == null || imageUrl.isBlank()) {
            return;
        }
        try {
            executor.execute(() -> recognizeAndRecord(accountId, imageUrl, observedAt));
        } catch (RejectedExecutionException exception) {
            log.warn("Sanity OCR queue is full; skip account {}", accountId);
        }
    }

    private void recognizeAndRecord(Long accountId, String imageUrl, LocalDateTime observedAt) {
        try {
            client.recognize(imageUrl)
                    .filter(result -> result.getConfidence() >= minimumConfidence)
                    .ifPresent(result -> accountRuntimeService.recordOcrSnapshot(
                            accountId, result.getCurrentSanity(), result.getMaxSanity(), observedAt));
        } catch (RuntimeException exception) {
            log.warn("Sanity OCR failed for account {}: {}", accountId, exception.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
    }

    private static Executor createExecutor() {
        return new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(100),
                runnable -> {
                    var thread = new Thread(runnable, "sanity-ocr");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
