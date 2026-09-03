package com.huanbao.aigateway.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.config.DataQueryServiceProperties;
import com.huanbao.aigateway.dto.DataQueryAuthorization;
import com.huanbao.aigateway.dto.DataQueryChatRequest;
import com.huanbao.aigateway.exception.GlobalExceptionHandler;
import com.huanbao.aigateway.security.DataQueryIdentity;
import com.huanbao.aigateway.security.DataQueryIdentityService;
import com.huanbao.aigateway.service.DataQueryAccessService;
import com.huanbao.aigateway.service.DataQueryRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

class DataQueryChatControllerTest {
    private static final DataQueryIdentity IDENTITY = new DataQueryIdentity(
        "user-1", "U1", "张三", "ORG-1", "组织一", "tenant-1", true, "SIGNED_HEADER"
    );
    private static final DataQueryAuthorization AUTHORIZATION = new DataQueryAuthorization(
        "user-1", "U1", "张三", "ORG-1", "组织一", "tenant-1", true, "SIGNED_HEADER",
        "USER_AUTHORIZED", false, List.of(), List.of("ORG-1", "ORG-2"), false
    );
    private static final String REQUEST_BODY = "{\"query\":\"查询发电量\",\"conversationId\":\"conversation-1\"}";

    @Test
    void missingPortalSignatureIsRejectedWith401() throws Exception {
        DataQueryIdentityService identityService = new DataQueryIdentityService(
            new ObjectMapper(),
            new DataQueryProperties(
                "unused", "unused", 1000, "SIGNED_HEADER", "test-secret", 300,
                false, 30, "USER_AUTHORIZED", false, "", false
            )
        );
        MockMvc mvc = mockMvc(
            identityService,
            mock(DataQueryAccessService.class),
            mock(DataQueryRateLimiter.class),
            RestClient.builder().build()
        );

        mvc.perform(post("/api/ai/data-query/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("IDENTITY_UNVERIFIED")));
    }

    @Test
    void authorizedRequestForwardsHeadersBodyAndCompleteSseStream() throws Exception {
        DataQueryIdentityService identityService = mock(DataQueryIdentityService.class);
        DataQueryAccessService accessService = mock(DataQueryAccessService.class);
        DataQueryRateLimiter rateLimiter = mock(DataQueryRateLimiter.class);
        when(identityService.resolve(
            any(DataQueryChatRequest.class),
            isNull(String.class),
            isNull(String.class),
            isNull(String.class),
            any(HttpServletRequest.class)
        )).thenReturn(IDENTITY);
        when(accessService.requireCovered(IDENTITY)).thenReturn(AUTHORIZATION);
        when(rateLimiter.tryAcquire("user-1")).thenReturn(true);

        String upstreamSse = "event: stage\n"
            + "data: {\"stage\":\"planning\"}\n\n"
            + ":ping\n\n"
            + "event: analysis_result\n"
            + "data: {\"result\":\"ok\"}\n\n"
            + "event: completed\n"
            + "data: {\"conversationId\":\"conversation-1\"}\n\n";
        RestClient.Builder clientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(clientBuilder).build();
        server.expect(requestTo("http://127.0.0.1:8089/api/query/execute"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-User-Id", "user-1"))
            .andExpect(header("X-Internal-Allowed-Orgs", "ORG-1,ORG-2"))
            .andExpect(content().json(REQUEST_BODY))
            .andRespond(withSuccess(upstreamSse, MediaType.TEXT_EVENT_STREAM));

        MockMvc mvc = mockMvc(
            identityService,
            accessService,
            rateLimiter,
            clientBuilder.build()
        );

        MvcResult initial = mvc.perform(post("/api/ai/data-query/chat")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY))
            .andReturn();
        MvcResult result = initial.getRequest().isAsyncStarted()
            ? mvc.perform(asyncDispatch(initial)).andReturn()
            : initial;

        String proxiedSse = result.getResponse().getContentAsString();
        assertTrue(proxiedSse.contains("planning"));
        assertTrue(proxiedSse.contains("{\"stage\":\"planning\"}"));
        assertTrue(proxiedSse.contains("event:analysis_result") || proxiedSse.contains("event: analysis_result"));
        assertTrue(proxiedSse.contains("event:completed") || proxiedSse.contains("event: completed"));
        assertTrue(proxiedSse.contains("ping"));
        server.verify();
    }

    private MockMvc mockMvc(
        DataQueryIdentityService identityService,
        DataQueryAccessService accessService,
        DataQueryRateLimiter rateLimiter,
        RestClient restClient
    ) {
        DataQueryServiceProperties properties = new DataQueryServiceProperties(
            new DataQueryServiceProperties.Service("http://127.0.0.1:8089"),
            Duration.ofSeconds(60)
        );
        DataQueryChatController controller = new DataQueryChatController(
            identityService,
            accessService,
            rateLimiter,
            restClient,
            properties,
            (Executor) Runnable::run
        );
        return MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }
}
