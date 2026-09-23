package com.px.base.incident.dto;

import lombok.Getter;

/**
 * 版本冲突异常（HTTP 409）。携带结构化冲突信息，
 * 由 GlobalExceptionHandler 输出到响应体 data（而不只是 message）。
 */
@Getter
public class IncidentVersionConflictException extends RuntimeException {

    private final transient IncidentConflictDTO conflict;

    public IncidentVersionConflictException(IncidentConflictDTO conflict) {
        super(conflict.getMessage());
        this.conflict = conflict;
    }
}
