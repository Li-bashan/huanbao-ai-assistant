package com.huanbao.aigateway.service;

import com.huanbao.aigateway.config.DataQueryProperties;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class DataQueryRateLimiter {
    private final DataQueryProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public DataQueryRateLimiter(DataQueryProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquire(String key) {
        String safeKey = key == null || key.isBlank() ? "anonymous" : key;
        long minute = Instant.now().getEpochSecond() / 60;
        Window window = windows.compute(safeKey, (ignored, current) -> {
            if (current == null || current.minute() != minute) {
                return new Window(minute, new AtomicInteger(1));
            }
            current.count().incrementAndGet();
            return current;
        });

        if (window.count().get() <= properties.safeMaxRequestsPerMinute()) return true;
        window.count().decrementAndGet();
        return false;
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
