package com.px.base.security;

/** 未登录（未选择当前操作人）：401 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
