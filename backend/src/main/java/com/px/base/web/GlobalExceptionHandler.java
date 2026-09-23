package com.px.base.web;

import com.px.base.dto.ResponseDTO;
import com.px.base.security.ForbiddenException;
import com.px.base.security.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：
 *  - 401 未登录、403 越权必须在服务端真实拒绝，数据不变更；
 *  - IllegalArgumentException 业务校验失败返回 400。
 * HTTP 状态码与响应体 code 保持一致，前端拦截器统一弹 message。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ResponseDTO<Void>> handleUnauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ResponseDTO.error(401, e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ResponseDTO<Void>> handleForbidden(ForbiddenException e) {
        log.warn("越权操作被拒绝: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ResponseDTO.error(403, e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseDTO<Void>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.error(400, e.getMessage()));
    }

    @ExceptionHandler(com.px.base.security.BusinessConflictException.class)
    public ResponseEntity<ResponseDTO<Void>> handleConflict(com.px.base.security.BusinessConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ResponseDTO.error(409, e.getMessage()));
    }

    /**
     * 事件整份版本冲突（409）：message 给人读，data 携带冲突字段与最新事件供前端渲染，
     * 前端用 data.latest.version 作为重新编辑的新版本基准。
     */
    @ExceptionHandler(com.px.base.security.IncidentVersionConflictException.class)
    public ResponseEntity<ResponseDTO<Object>> handleIncidentConflict(com.px.base.security.IncidentVersionConflictException e) {
        log.warn("事件版本冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ResponseDTO.<Object>builder().code(409).message(e.getMessage()).data(e.getPayload()).build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDTO<Void>> handleOther(Exception e) {
        log.error("系统异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDTO.error(500, "系统异常: " + e.getMessage()));
    }
}
