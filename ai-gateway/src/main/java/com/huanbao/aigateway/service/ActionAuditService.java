package com.huanbao.aigateway.service;

import com.huanbao.aigateway.dto.ActionAuditCreateRequest;
import com.huanbao.aigateway.dto.ActionAuditResponse;
import com.huanbao.aigateway.dto.PageResponse;
import com.huanbao.aigateway.repository.ActionAuditRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;

@Service
public class ActionAuditService {
    private final ActionAuditRepository repository;

    public ActionAuditService(ActionAuditRepository repository) {
        this.repository = repository;
    }

    public ActionAuditResponse save(ActionAuditCreateRequest request, String clientIp, String userAgent) {
        return repository.save(request, clientIp, userAgent);
    }

    public PageResponse<ActionAuditResponse> search(
        String userId,
        String action,
        String formCode,
        String result,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        int page,
        int size
    ) {
        return repository.search(userId, action, formCode, result, startTime, endTime, page, size);
    }
}
