package com.huanbao.aigateway.service;

import com.huanbao.aigateway.dto.WorkflowValidateRequest;
import com.huanbao.aigateway.dto.WorkflowValidateResponse;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class WorkflowActionValidateService {
    private static final Set<String> ALLOWED_ACTIONS = Set.of("open_form", "open_menu");

    public WorkflowValidateResponse validate(WorkflowValidateRequest request) {
        if (!"verified".equals(request.status())) {
            return new WorkflowValidateResponse(false, "only verified workflow action is allowed");
        }

        if (!ALLOWED_ACTIONS.contains(request.action())) {
            return new WorkflowValidateResponse(false, "action must be open_form or open_menu");
        }

        return new WorkflowValidateResponse(true, "ok");
    }
}
