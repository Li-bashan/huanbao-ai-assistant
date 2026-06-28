package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import com.huanbao.aigateway.dto.WorkflowValidateRequest;
import com.huanbao.aigateway.dto.WorkflowValidateResponse;
import com.huanbao.aigateway.service.WorkflowActionValidateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/workflow/actions")
public class WorkflowActionController {
    private final WorkflowActionValidateService validateService;

    public WorkflowActionController(WorkflowActionValidateService validateService) {
        this.validateService = validateService;
    }

    @PostMapping("/validate")
    public ApiResponse<WorkflowValidateResponse> validate(@Valid @RequestBody WorkflowValidateRequest request) {
        return ApiResponse.ok(validateService.validate(request));
    }
}
