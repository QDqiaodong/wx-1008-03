package com.px.base.security;

/** 已登录但无权执行该操作：403。服务层真实抛出，不依赖前端按钮显隐。 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
