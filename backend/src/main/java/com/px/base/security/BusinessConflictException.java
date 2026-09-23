package com.px.base.security;

/**
 * 业务状态冲突（HTTP 409）：值守状态机不允许该操作，
 * 例如“操作员未到位不能就绪”“终态不能改派”。
 * 与 400（参数/规则不满足）、403（越权）区分开。
 */
public class BusinessConflictException extends RuntimeException {
    public BusinessConflictException(String message) {
        super(message);
    }
}
