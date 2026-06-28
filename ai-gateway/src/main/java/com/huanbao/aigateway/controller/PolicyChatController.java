package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import com.huanbao.aigateway.dto.PolicyChatRequest;
import com.huanbao.aigateway.dto.PolicyChatResponse;
import com.huanbao.aigateway.service.PolicyChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/policy")
public class PolicyChatController {
    private final PolicyChatService policyChatService;

    public PolicyChatController(PolicyChatService policyChatService) {
        this.policyChatService = policyChatService;
    }

    @PostMapping("/chat")
    public ApiResponse<PolicyChatResponse> chat(@Valid @RequestBody PolicyChatRequest request) {
        return ApiResponse.ok(policyChatService.chat(request));
    }
}
