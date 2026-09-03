package com.huanbao.dataquery.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SseStreamDispatcherTest {

    private final ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
    private final ExecutorService executionExecutor = mock(ExecutorService.class);
    private final SseStreamDispatcher dispatcher = new SseStreamDispatcher(
            heartbeatExecutor, executionExecutor);

    @AfterEach
    void shutdown() {
        dispatcher.shutdown();
    }

    @Test
    void schedulesTenSecondPingAndCanSendJsonEvent() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        org.mockito.Mockito.when(heartbeatExecutor.scheduleAtFixedRate(
                any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(invocation -> future);

        SseEmitter created = dispatcher.createEmitter();
        assertTrue(created != null);
        verify(heartbeatExecutor).scheduleAtFixedRate(
                any(Runnable.class), org.mockito.Mockito.eq(10L), org.mockito.Mockito.eq(10L),
                org.mockito.Mockito.eq(TimeUnit.SECONDS));

        assertTrue(dispatcher.send(emitter, "stage", java.util.Map.of("message", "ok")));
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void brokenPipeCompletesEmitterAndReturnsFalse() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        assertFalse(dispatcher.send(emitter, "stage", java.util.Map.of("message", "ok")));

        verify(emitter).complete();
    }
}
