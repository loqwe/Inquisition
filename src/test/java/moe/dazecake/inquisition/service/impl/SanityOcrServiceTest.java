package moe.dazecake.inquisition.service.impl;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SanityOcrServiceTest {

    @Test
    void acceptedRecognitionUpdatesAccountRuntime() {
        var client = mock(SanityOcrClient.class);
        var runtimeService = mock(AccountRuntimeService.class);
        when(client.isConfigured()).thenReturn(true);
        var service = new SanityOcrService(client, runtimeService, Runnable::run, true, 0.80d);
        var observedAt = LocalDateTime.of(2026, 7, 21, 16, 0);
        when(client.recognize("https://inquisition-img.example/one.png"))
                .thenReturn(Optional.of(new SanityOcrResult(1, 210, 0.92d, 3)));

        service.submit(398L, "https://inquisition-img.example/one.png", observedAt);

        verify(runtimeService).recordOcrSnapshot(398L, 1, 210, observedAt);
    }

    @Test
    void lowConfidenceRecognitionDoesNotChangeAccountRuntime() {
        var client = mock(SanityOcrClient.class);
        var runtimeService = mock(AccountRuntimeService.class);
        when(client.isConfigured()).thenReturn(true);
        var service = new SanityOcrService(client, runtimeService, Runnable::run, true, 0.80d);
        when(client.recognize("https://inquisition-img.example/unclear.png"))
                .thenReturn(Optional.of(new SanityOcrResult(1, 210, 0.60d, 2)));

        service.submit(398L, "https://inquisition-img.example/unclear.png", LocalDateTime.now());

        verify(runtimeService, never()).recordOcrSnapshot(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabledRecognitionDoesNotCallSidecar() {
        var client = mock(SanityOcrClient.class);
        var runtimeService = mock(AccountRuntimeService.class);
        var service = new SanityOcrService(client, runtimeService, Runnable::run, false, 0.80d);

        service.submit(398L, "https://inquisition-img.example/one.png", LocalDateTime.now());

        verify(client, never()).recognize(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void enabledRecognitionRejectsMissingClientConfiguration() {
        var client = mock(SanityOcrClient.class);

        assertThrows(IllegalStateException.class, () -> new SanityOcrService(
                client, mock(AccountRuntimeService.class), Runnable::run, true, 0.80d));
    }
}
