package com.uniride.agent.support;

import org.springframework.http.HttpStatus;

public class AgentProviderException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AgentProviderException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
