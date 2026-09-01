package com.huanbao.aigateway.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.Valid;

public record DataQueryAccessRequest(
    @JsonAlias({"untrustedClientContext"}) @Valid DataQueryUserContext userContext
) {
}
