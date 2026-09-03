package com.huanbao.aigateway.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.aigateway.config.DataQueryProperties;
import com.huanbao.aigateway.dto.DataQueryAccessRequest;
import com.huanbao.aigateway.dto.DataQueryChatRequest;
import com.huanbao.aigateway.dto.DataQueryUserContext;
import com.huanbao.aigateway.dto.PolicyChatRequest;
import com.huanbao.aigateway.dto.OfficeChatRequest;
import com.huanbao.aigateway.dto.MasterChatRequest;
import com.huanbao.aigateway.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DataQueryIdentityService {
    private static final Logger log = LoggerFactory.getLogger(DataQueryIdentityService.class);
    public static final String IDENTITY_HEADER = "X-Portal-Identity";
    public static final String TIMESTAMP_HEADER = "X-Portal-Identity-Timestamp";
    public static final String SIGNATURE_HEADER = "X-Portal-Identity-Signature";
    private static final String CLIENT_USER_ID_HEADER = "X-User-Id";
    private static final String PERMISSIVE_MODE = "PERMISSIVE";
    private static final String DEFAULT_USER_ID = "liu_haopeng";
    private static final String DEFAULT_USER_CODE = "liu_haopeng";
    private static final String DEFAULT_USER_NAME = "刘昊澎";

    private final ObjectMapper objectMapper;
    private final DataQueryProperties properties;

    public DataQueryIdentityService(ObjectMapper objectMapper, DataQueryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public DataQueryIdentity resolve(
        DataQueryChatRequest request,
        String signedIdentity,
        String timestamp,
        String signature
    ) {
        return resolve(request, signedIdentity, timestamp, signature, null);
    }

    public DataQueryIdentity resolve(
        DataQueryChatRequest request,
        String signedIdentity,
        String timestamp,
        String signature,
        HttpServletRequest servletRequest
    ) {
        if ("BODY_TRIAL".equals(properties.safeIdentityMode())) {
            if (!properties.allowBodyIdentityTrial()) {
                throw new BusinessException("IDENTITY_UNVERIFIED", "body identity trial is disabled");
            }
            return fromContext(request.userContext(), false, "BODY_TRIAL");
        }

        if (PERMISSIVE_MODE.equals(properties.safeIdentityMode())
            && !hasSignedIdentityHeaders(signedIdentity, timestamp, signature)) {
            return resolvePermissiveContext(request == null ? null : request.userContext(), servletRequest);
        }

        return resolveSignedContext(signedIdentity, timestamp, signature);
    }

    public DataQueryIdentity resolve(
        DataQueryAccessRequest request,
        String signedIdentity,
        String timestamp,
        String signature
    ) {
        return resolve(request, signedIdentity, timestamp, signature, null);
    }

    public DataQueryIdentity resolve(
        DataQueryAccessRequest request,
        String signedIdentity,
        String timestamp,
        String signature,
        HttpServletRequest servletRequest
    ) {
        if ("BODY_TRIAL".equals(properties.safeIdentityMode())) {
            if (!properties.allowBodyIdentityTrial()) {
                throw new BusinessException("IDENTITY_UNVERIFIED", "body identity trial is disabled");
            }
            return fromContext(request.userContext(), false, "BODY_TRIAL");
        }

        if (PERMISSIVE_MODE.equals(properties.safeIdentityMode())
            && !hasSignedIdentityHeaders(signedIdentity, timestamp, signature)) {
            return resolvePermissiveContext(request == null ? null : request.userContext(), servletRequest);
        }

        return resolveSignedContext(signedIdentity, timestamp, signature);
    }

    public DataQueryIdentity resolve(
        PolicyChatRequest request,
        String signedIdentity,
        String timestamp,
        String signature
    ) {
        if ("BODY_TRIAL".equals(properties.safeIdentityMode())) {
            if (!properties.allowBodyIdentityTrial()) {
                throw new BusinessException("IDENTITY_UNVERIFIED", "body identity trial is disabled");
            }
            return fromContext(request.userContext(), false, "BODY_TRIAL");
        }
        return resolveSignedContext(signedIdentity, timestamp, signature);
    }

    public DataQueryIdentity resolve(
        OfficeChatRequest request,
        String signedIdentity,
        String timestamp,
        String signature
    ) {
        if ("BODY_TRIAL".equals(properties.safeIdentityMode())) {
            if (!properties.allowBodyIdentityTrial()) {
                throw new BusinessException("IDENTITY_UNVERIFIED", "body identity trial is disabled");
            }
            return fromContext(request.userContext(), false, "BODY_TRIAL");
        }
        return resolveSignedContext(signedIdentity, timestamp, signature);
    }

    public DataQueryIdentity resolve(
        MasterChatRequest request,
        String signedIdentity,
        String timestamp,
        String signature
    ) {
        if ("BODY_TRIAL".equals(properties.safeIdentityMode())) {
            if (!properties.allowBodyIdentityTrial()) {
                throw new BusinessException("IDENTITY_UNVERIFIED", "body identity trial is disabled");
            }
            return fromContext(request.userContext(), false, "BODY_TRIAL");
        }
        return resolveSignedContext(signedIdentity, timestamp, signature);
    }

    private DataQueryIdentity resolveSignedContext(
        String signedIdentity,
        String timestamp,
        String signature
    ) {
        if (!StringUtils.hasText(properties.identitySecret())) {
            throw new BusinessException("IDENTITY_CONFIG_MISSING", "signed portal identity is not configured");
        }
        if (!StringUtils.hasText(signedIdentity)
            || !StringUtils.hasText(timestamp)
            || !StringUtils.hasText(signature)) {
            throw new BusinessException("IDENTITY_UNVERIFIED", "signed portal identity is required");
        }
        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            throw new BusinessException("IDENTITY_UNVERIFIED", "identity timestamp is invalid");
        }

        long age = Math.abs(Instant.now().getEpochSecond() - timestampSeconds);
        if (age > properties.safeClockSkewSeconds()) {
            throw new BusinessException("IDENTITY_UNVERIFIED", "identity timestamp has expired");
        }

        String expected = hmac(timestamp.trim() + "." + signedIdentity);
        if (!constantTimeEquals(expected, signature.trim())) {
            throw new BusinessException("IDENTITY_UNVERIFIED", "identity signature is invalid");
        }

        try {
            String json = decodeIdentity(signedIdentity);
            JsonNode node = objectMapper.readTree(json);
            DataQueryUserContext context = new DataQueryUserContext(
                text(node, "userId"),
                text(node, "userCode"),
                text(node, "userName"),
                text(node, "orgCode"),
                text(node, "orgName"),
                text(node, "tenantId")
            );
            return fromContext(context, true, "SIGNED_HEADER");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException("IDENTITY_UNVERIFIED", "signed portal identity is invalid");
        }
    }

    private DataQueryIdentity fromContext(DataQueryUserContext context, boolean verified, String source) {
        if (context == null || !StringUtils.hasText(context.userId()) || !StringUtils.hasText(context.userName())) {
            throw new BusinessException("CURRENT_USER_MISSING", "userId and userName are required");
        }
        return new DataQueryIdentity(
            trim(context.userId()),
            trim(context.userCode()),
            trim(context.userName()),
            trim(context.orgCode()),
            trim(context.orgName()),
            trim(context.tenantId()),
            verified,
            source
        );
    }

    private DataQueryIdentity resolvePermissiveContext(
        DataQueryUserContext bodyContext,
        HttpServletRequest servletRequest
    ) {
        String requestedUser = clientUserId(servletRequest);
        if (!StringUtils.hasText(requestedUser) && bodyContext != null) {
            requestedUser = firstText(
                bodyContext.userId(), bodyContext.userCode(), bodyContext.userName()
            );
        }

        DataQueryUserContext context = knownTestUser(requestedUser);
        if (context == null && bodyContext != null
            && StringUtils.hasText(bodyContext.userId())
            && StringUtils.hasText(bodyContext.userName())) {
            context = bodyContext;
        }
        if (context == null && StringUtils.hasText(requestedUser)) {
            String normalized = requestedUser.trim();
            context = new DataQueryUserContext(
                normalized, normalized, normalized, "", "", "default"
            );
        }
        if (context == null) {
            context = new DataQueryUserContext(
                DEFAULT_USER_ID, DEFAULT_USER_CODE, DEFAULT_USER_NAME, "", "", "default"
            );
        }

        log.warn("DATA_QUERY_PERMISSIVE_IDENTITY source={} verified=false", PERMISSIVE_MODE);
        return fromContext(context, false, PERMISSIVE_MODE);
    }

    private String clientUserId(HttpServletRequest servletRequest) {
        if (servletRequest == null) return "";
        String header = servletRequest.getHeader(CLIENT_USER_ID_HEADER);
        if (StringUtils.hasText(header)) return header.trim();

        Cookie[] cookies = servletRequest.getCookies();
        if (cookies == null) return "";
        for (Cookie cookie : cookies) {
            String name = cookie.getName();
            if ("X-User-Id".equalsIgnoreCase(name)
                || "userId".equalsIgnoreCase(name)
                || "user_id".equalsIgnoreCase(name)
                || "userCode".equalsIgnoreCase(name)
                || "user_code".equalsIgnoreCase(name)) {
                if (StringUtils.hasText(cookie.getValue())) return cookie.getValue().trim();
            }
        }
        return "";
    }

    private DataQueryUserContext knownTestUser(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("liuhaopeng")
            || normalized.equals("liu_haopeng")
            || normalized.equals("liu-haopeng")
            || value.trim().equals(DEFAULT_USER_NAME)) {
            return new DataQueryUserContext(
                DEFAULT_USER_ID, DEFAULT_USER_CODE, DEFAULT_USER_NAME, "", "", "default"
            );
        }
        if (normalized.equals("chiquanhu")
            || normalized.equals("chi_quanhu")
            || normalized.equals("chi-quanhu")
            || value.trim().equals("迟全虎")) {
            return new DataQueryUserContext(
                "chi_quanhu", "chi_quanhu", "迟全虎", "", "", "default"
            );
        }
        return null;
    }

    private boolean hasSignedIdentityHeaders(String signedIdentity, String timestamp, String signature) {
        return StringUtils.hasText(signedIdentity)
            || StringUtils.hasText(timestamp)
            || StringUtils.hasText(signature);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private String decodeIdentity(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.identitySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException("IDENTITY_CONFIG_MISSING", "identity signing is unavailable");
        }
    }

    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) result.append(String.format("%02x", item));
        return result.toString();
    }
}
