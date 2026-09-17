package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.config.DataQueryServiceProperties;
import com.huanbao.aigateway.dto.DataQueryAuthorization;
import com.huanbao.aigateway.dto.DataQueryChatRequest;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.security.DataQueryIdentity;
import com.huanbao.aigateway.security.DataQueryIdentityService;
import com.huanbao.aigateway.service.DataQueryAccessService;
import com.huanbao.aigateway.service.DataQueryRateLimiter;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The data-query gateway is deliberately limited to security and transport.
 * Query planning, calculation and data access belong to huanbao-dataquery.
 */
@RestController
@RequestMapping("/api/ai/data-query")
public class DataQueryChatController {
    private static final Logger log = LoggerFactory.getLogger(DataQueryChatController.class);
    private static final long SSE_TIMEOUT_MS = 60_000L;
    private static final String EXECUTE_PATH = "/api/query/execute";

    private final DataQueryIdentityService identityService;
    private final DataQueryAccessService accessService;
    private final DataQueryRateLimiter rateLimiter;
    private final RestClient dataQueryRestClient;
    private final DataQueryServiceProperties serviceProperties;
    private final Executor proxyExecutor;

    public DataQueryChatController(
        DataQueryIdentityService identityService,
        DataQueryAccessService accessService,
        DataQueryRateLimiter rateLimiter,
        @Qualifier("dataQueryRestClient") RestClient dataQueryRestClient,
        DataQueryServiceProperties serviceProperties,
        @Qualifier("dataQueryProxyExecutor") Executor proxyExecutor
    ) {
        this.identityService = identityService;
        this.accessService = accessService;
        this.rateLimiter = rateLimiter;
        this.dataQueryRestClient = dataQueryRestClient;
        this.serviceProperties = serviceProperties;
        this.proxyExecutor = proxyExecutor;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(
        HttpServletRequest servletRequest,
        @RequestHeader(value = DataQueryIdentityService.IDENTITY_HEADER, required = false) String signedIdentity,
        @RequestHeader(value = DataQueryIdentityService.TIMESTAMP_HEADER, required = false) String timestamp,
        @RequestHeader(value = DataQueryIdentityService.SIGNATURE_HEADER, required = false) String signature,
        @Valid @RequestBody DataQueryChatRequest request
    ) {
        DataQueryIdentity identity = identityService.resolve(
            request, signedIdentity, timestamp, signature, servletRequest
        );
        DataQueryAuthorization authorization = accessService.requireCovered(identity);
        if (!rateLimiter.tryAcquire(authorization.userId())) {
            throw new BusinessException("RATE_LIMITED", "data query rate limit exceeded");
        }

        String conversationId = StringUtils.hasText(request.conversationId())
            ? request.conversationId().trim()
            : "hb_" + UUID.randomUUID();
        String allowedOrgs = allowedOrganizations(authorization);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        ProxyConnection connection = new ProxyConnection();
        emitter.onCompletion(connection::close);
        emitter.onTimeout(connection::close);
        emitter.onError(error -> connection.close());

        proxyExecutor.execute(() -> proxy(
            new QueryExecuteRequest(request.query(), conversationId),
            authorization,
            allowedOrgs,
            emitter,
            connection
        ));

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONNECTION, "keep-alive")
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(emitter);
    }

    private void proxy(
        QueryExecuteRequest request,
        DataQueryAuthorization authorization,
        String allowedOrgs,
        SseEmitter emitter,
        ProxyConnection connection
    ) {
        try {
            dataQueryRestClient.post()
                .uri(serviceProperties.executeUrl(EXECUTE_PATH))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("X-Internal-User-Id", authorization.userId())
                .header("X-Internal-Allowed-Orgs", allowedOrgs)
                .body(request)
                .exchange((clientRequest, clientResponse) -> {
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new DataQueryUpstreamException(clientResponse.getStatusCode().value());
                    }
                    InputStream responseBody = clientResponse.getBody();
                    connection.attach(responseBody);
                    forwardSse(responseBody, emitter, connection);
                    return null;
                });
            if (!connection.isClosed()) {
                emitter.complete();
            }
        } catch (RuntimeException exception) {
            if (!connection.isClosed()) {
                log.warn("DATA_QUERY_PROXY_FAILED status={}",
                    exception instanceof DataQueryUpstreamException upstream
                        ? upstream.status
                        : "network", exception);
                emitter.completeWithError(exception);
            }
        } finally {
            connection.close();
        }
    }

    private void forwardSse(InputStream input, SseEmitter emitter, ProxyConnection connection)
        throws IOException {
        if (input == null) {
            return;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        SseEvent event = new SseEvent();
        String line;
        while (!connection.isClosed() && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (!sendEvent(event, emitter, connection)) {
                    return;
                }
                event = new SseEvent();
                continue;
            }
            if (line.charAt(0) == ':') {
                if (!sendComment(optionalSseSpace(line.substring(1)), emitter, connection)) {
                    return;
                }
                continue;
            }

            int separator = line.indexOf(':');
            String field = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0 ? "" : optionalSseSpace(line.substring(separator + 1));
            switch (field) {
                case "event" -> event.name = value;
                case "data" -> event.data.add(value);
                case "id" -> event.id = value;
                case "retry" -> event.retry = parseRetry(value);
                default -> {
                    // Ignore fields not defined by the SSE protocol.
                }
            }
        }

        if (!connection.isClosed()) {
            sendEvent(event, emitter, connection);
        }
    }

    private boolean sendEvent(SseEvent event, SseEmitter emitter, ProxyConnection connection)
        throws IOException {
        if (event.name == null && event.id == null && event.retry == null && event.data.isEmpty()) {
            return true;
        }
        if (connection.isClosed()) {
            return false;
        }

        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        if (event.name != null) {
            builder.name(event.name);
        }
        if (event.id != null) {
            builder.id(event.id);
        }
        if (event.retry != null) {
            builder.reconnectTime(event.retry);
        }
        for (String data : event.data) {
            builder.data(data);
        }
        try {
            emitter.send(builder);
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            connection.close();
            return false;
        }
    }

    private boolean sendComment(String comment, SseEmitter emitter, ProxyConnection connection)
        throws IOException {
        if (connection.isClosed()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().comment(comment));
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            connection.close();
            return false;
        }
    }

    private Long parseRetry(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String optionalSseSpace(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private String allowedOrganizations(DataQueryAuthorization authorization) {
        List<String> values = authorization.allowedOrgCodes() == null
            ? List.of()
            : authorization.allowedOrgCodes().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        boolean wildcardOnly = values.size() == 1
            && DataQueryAccessService.ALL_ORGANIZATIONS_WILDCARD.equals(values.get(0));
        if (wildcardOnly) {
            return DataQueryAccessService.ALL_ORGANIZATIONS_WILDCARD;
        }
        if (!values.isEmpty()) {
            return String.join(",", values);
        }
        if (authorization.allowAllOrganizations()) {
            return DataQueryAccessService.ALL_ORGANIZATIONS_WILDCARD;
        }
        throw new BusinessException(
            "DATA_SCOPE_DENIED",
            "no allowed organization scope is configured"
        );
    }

    private record QueryExecuteRequest(String query, String conversationId) {
    }

    private static final class SseEvent {
        private String name;
        private String id;
        private Long retry;
        private final List<String> data = new ArrayList<>();
    }

    private static final class ProxyConnection {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<InputStream> input = new AtomicReference<>();

        private void attach(InputStream value) {
            if (closed.get()) {
                closeQuietly(value);
                return;
            }
            input.set(value);
            if (closed.get()) {
                close();
            }
        }

        private boolean isClosed() {
            return closed.get();
        }

        private void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(input.getAndSet(null));
        }

        private static void closeQuietly(InputStream value) {
            if (value == null) {
                return;
            }
            try {
                value.close();
            } catch (IOException ignored) {
                // The client is already gone or the upstream connection is closing.
            }
        }
    }

    private static final class DataQueryUpstreamException extends RuntimeException {
        private final int status;

        private DataQueryUpstreamException(int status) {
            super("huanbao-dataquery returned HTTP " + status);
            this.status = status;
        }
    }
}
