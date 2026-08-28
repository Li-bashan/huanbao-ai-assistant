package com.huanbao.aigateway.service;

import com.huanbao.aigateway.dto.DifyDataQueryRequest;
import com.huanbao.aigateway.dto.DifyDataQueryResponse;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.repository.DifyDataQueryRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class DifyDataQueryService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 366;

    private final DifyDataQueryRepository repository;
    private final DataQueryAccessService accessService;

    public DifyDataQueryService(
        DifyDataQueryRepository repository,
        DataQueryAccessService accessService
    ) {
        this.repository = repository;
        this.accessService = accessService;
    }

    public DifyDataQueryResponse query(DifyDataQueryRequest request) {
        accessService.requireCovered(request.userName());
        List<Map<String, Object>> records = switch (request.queryCode()) {
            case "action_audit_recent" -> recent(request.safeParams());
            case "action_audit_summary" -> summary(request.safeParams());
            default -> throw new BusinessException(
                "QUERY_CODE_NOT_ALLOWED",
                "unsupported queryCode: " + request.queryCode()
            );
        };
        return new DifyDataQueryResponse(request.queryCode(), records.size(), records);
    }

    private List<Map<String, Object>> recent(Map<String, Object> params) {
        String userId = optionalText(params, "userId", 80);
        String action = optionalText(params, "action", 40);
        int limit = boundedInt(params, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        return repository.findRecentActionAudits(userId, action, limit);
    }

    private List<Map<String, Object>> summary(Map<String, Object> params) {
        int days = boundedInt(params, "days", DEFAULT_DAYS, 1, MAX_DAYS);
        return repository.summarizeActionAudits(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private String optionalText(Map<String, Object> params, String name, int maxLength) {
        Object raw = params.get(name);
        if (raw == null || !StringUtils.hasText(raw.toString())) {
            return null;
        }
        String value = raw.toString().trim();
        if (value.length() > maxLength) {
            throw new BusinessException("INVALID_QUERY_PARAM", name + " is too long");
        }
        return value;
    }

    private int boundedInt(
        Map<String, Object> params,
        String name,
        int defaultValue,
        int min,
        int max
    ) {
        Object raw = params.get(name);
        if (raw == null || !StringUtils.hasText(raw.toString())) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.toString());
            if (value < min || value > max) {
                throw new BusinessException(
                    "INVALID_QUERY_PARAM",
                    name + " must be between " + min + " and " + max
                );
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new BusinessException("INVALID_QUERY_PARAM", name + " must be an integer");
        }
    }
}
