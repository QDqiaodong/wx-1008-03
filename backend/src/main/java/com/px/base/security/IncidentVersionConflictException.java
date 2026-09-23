package com.px.base.security;

/**
 * 整份事件版本冲突（HTTP 409）：客户端携带的 expectedVersion 已过期。
 * 必须携带结构化 payload（最新版本 + 冲突字段清单），由全局异常处理器原样返回，
 * 前端据此提示“谁在何时先改了哪些字段”，而不是只给一句笼统的冲突文案。
 */
public class IncidentVersionConflictException extends RuntimeException {

    private final transient Object payload;

    public IncidentVersionConflictException(String message, Object payload) {
        super(message);
        this.payload = payload;
    }

    public Object getPayload() {
        return payload;
    }
}
