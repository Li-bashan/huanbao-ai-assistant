package com.huanbao.dataquery.protocol;

import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring SseEmitter 的安全发送和心跳封装。
 *
 * <p>所有业务事件和 comment 心跳都经过同一把连接锁，避免两个线程同时写入
 * 一个 SSE 响应。客户端 Broken pipe 时只清理本地资源，不再尝试写错误事件。</p>
 */
@Component
public class SseStreamDispatcher {

    public static final long HEARTBEAT_INTERVAL_SECONDS = 10L;

    private final ScheduledExecutorService heartbeatExecutor;
    private final ExecutorService executionExecutor;
    private final ConcurrentMap<SseEmitter, Connection> connections = new ConcurrentHashMap<>();

    public SseStreamDispatcher() {
        this(
                Executors.newScheduledThreadPool(1, daemonFactory("data-query-sse-heartbeat")),
                Executors.newCachedThreadPool(daemonFactory("data-query-execution")));
    }

    SseStreamDispatcher(
            ScheduledExecutorService heartbeatExecutor,
            ExecutorService executionExecutor) {
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
        this.executionExecutor = Objects.requireNonNull(executionExecutor, "executionExecutor");
    }

    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        Connection connection = new Connection();
        connections.put(emitter, connection);
        emitter.onCompletion(() -> close(emitter));
        emitter.onTimeout(() -> close(emitter));
        emitter.onError(error -> close(emitter));
        connection.heartbeat = heartbeatExecutor.scheduleAtFixedRate(
                () -> sendComment(emitter),
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        return emitter;
    }

    /** 在后台执行阻塞的查询流水线，避免占用 MVC 请求线程。 */
    public void dispatch(SseEmitter emitter, Runnable task) {
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(task, "task");
        executionExecutor.execute(task);
    }

    /** 安全推送业务事件；成功返回 true，断连时完成并释放 emitter。 */
    public boolean send(SseEmitter emitter, String eventName, Object payload) {
        Connection connection = connections.computeIfAbsent(emitter, ignored -> new Connection());
        synchronized (connection.lock) {
            if (connection.closed.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(payload, MediaType.APPLICATION_JSON));
                return true;
            } catch (IOException | IllegalStateException disconnected) {
                close(emitter, connection);
                return false;
            }
        }
    }

    /** 推送 SSE comment 心跳，线上表现为 :ping\n\n。 */
    public boolean sendComment(SseEmitter emitter) {
        Connection connection = connections.computeIfAbsent(emitter, ignored -> new Connection());
        synchronized (connection.lock) {
            if (connection.closed.get()) {
                return false;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
                return true;
            } catch (IOException | IllegalStateException disconnected) {
                close(emitter, connection);
                return false;
            }
        }
    }

    public void complete(SseEmitter emitter) {
        Connection connection = connections.get(emitter);
        if (connection == null) {
            emitter.complete();
            return;
        }
        close(emitter, connection);
    }

    private void close(SseEmitter emitter) {
        Connection connection = connections.get(emitter);
        if (connection != null) {
            close(emitter, connection);
        }
    }

    private void close(SseEmitter emitter, Connection connection) {
        if (!connection.closed.compareAndSet(false, true)) {
            return;
        }
        connections.remove(emitter, connection);
        ScheduledFuture<?> heartbeat = connection.heartbeat;
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        emitter.complete();
    }

    @PreDestroy
    public void shutdown() {
        connections.keySet().forEach(this::complete);
        heartbeatExecutor.shutdownNow();
        executionExecutor.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class Connection {
        private final Object lock = new Object();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile ScheduledFuture<?> heartbeat;
    }
}
