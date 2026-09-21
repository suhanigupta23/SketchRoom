package com.sketchroom.controller;

import com.sketchroom.dto.RoomDtos.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", error.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ErrorResponse> storageFailure(Exception error) {
        log.error("Storage request failed", error);
        return ResponseEntity.status(503).body(new ErrorResponse("SERVICE_UNAVAILABLE", "Storage is unavailable. Please try again shortly."));
    }
}
