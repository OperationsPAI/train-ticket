package com.trainticket.postsales.api;

import com.trainticket.platformkit.http.ApiError;
import com.trainticket.platformkit.http.CorrelationIds;
import com.trainticket.postsales.application.OrderNotRefundableException;
import com.trainticket.postsales.application.PostSalesConcurrencyException;
import com.trainticket.postsales.application.RefundAlreadyInProgressException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = PostSalesController.class)
public class PostSalesExceptionHandler {
    @ExceptionHandler(RefundAlreadyInProgressException.class)
    public ResponseEntity<ApiError> handleRefundAlreadyInProgress(RefundAlreadyInProgressException exception, HttpServletRequest request) {
        Map<String, ?> details = exception.existingCaseId() == null ? Map.of() : Map.of("existingCaseId", prefixed(exception.existingCaseId()));
        return error(HttpStatus.CONFLICT, "REFUND_ALREADY_IN_PROGRESS", exception.getMessage(), request, details);
    }

    @ExceptionHandler(PostSalesConcurrencyException.class)
    public ResponseEntity<ApiError> handleConcurrency(PostSalesConcurrencyException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "REFUND_ALREADY_IN_PROGRESS", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(OrderNotRefundableException.class)
    public ResponseEntity<ApiError> handleOrderNotRefundable(OrderNotRefundableException exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_NOT_REFUNDABLE", exception.getMessage(), request, Map.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request, Map<String, ?> details) {
        return ResponseEntity.status(status).body(new ApiError(code, message, CorrelationIds.from(request), details));
    }

    private static String prefixed(String caseId) {
        return caseId == null || caseId.startsWith("psc-") ? caseId : "psc-" + caseId;
    }
}
