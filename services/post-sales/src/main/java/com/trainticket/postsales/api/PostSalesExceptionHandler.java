package com.trainticket.postsales.api;

import com.trainticket.platformkit.http.ApiError;
import com.trainticket.platformkit.http.CorrelationIds;
import com.trainticket.postsales.application.OrderNotRefundableException;
import com.trainticket.postsales.application.PolicyContextUnavailableException;
import com.trainticket.postsales.application.PostSalesConcurrencyException;
import com.trainticket.postsales.application.RefundAlreadyInProgressException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackageClasses = PostSalesController.class)
public class PostSalesExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(PostSalesExceptionHandler.class);

    @ExceptionHandler(RefundAlreadyInProgressException.class)
    public ResponseEntity<ApiError> handleRefundAlreadyInProgress(RefundAlreadyInProgressException exception, HttpServletRequest request) {
        Map<String, ?> details = exception.existingCaseId() == null ? Map.of() : Map.of("existingCaseId", prefixed(exception.existingCaseId()));
        return error(HttpStatus.CONFLICT, "REFUND_ALREADY_IN_PROGRESS", exception.getMessage(), request, details);
    }

    @ExceptionHandler(PostSalesConcurrencyException.class)
    public ResponseEntity<ApiError> handleConcurrency(PostSalesConcurrencyException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "REFUND_ALREADY_IN_PROGRESS", exception.getMessage(), request, Map.of());
    }

    /**
     * 409 rather than 500: the caller can and should retry. The context is
     * projected from a journey-order event, so a refund request can outrun it by a
     * second or two, and refusing is the only alternative to pricing the refund
     * from a fallback that forces a 100% penalty.
     */
    @ExceptionHandler(PolicyContextUnavailableException.class)
    public ResponseEntity<ApiError> handlePolicyContextUnavailable(
            PolicyContextUnavailableException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "POLICY_CONTEXT_NOT_READY", exception.getMessage(), request,
            Map.of("journeyOrderId", exception.journeyOrderId()));
    }

    @ExceptionHandler(OrderNotRefundableException.class)
    public ResponseEntity<ApiError> handleOrderNotRefundable(OrderNotRefundableException exception, HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_NOT_REFUNDABLE", exception.getMessage(), request, Map.of());
    }

    /**
     * Catch-all so an unmapped exception cannot become a silent 500.
     *
     * Without this, anything not named above fell through to Spring's default
     * handler, which returns 500 and logs nothing at all. `POST
     * /post-sales-cases/{id}/evaluate` was returning 500 in the e2e suite with no
     * corresponding line in this service's log and none in fare-pricing's either,
     * so there was no way to tell whether the fault was local, in the adjustment
     * quote call, or in the policy engine. That is the same "failure produces no
     * signal" shape as the swallowed handler exceptions in
     * PostSalesEventHandler.
     *
     * Still a 500 -- an unexpected exception IS a server error and dressing it up
     * as anything else would be worse. The change is that it now says so, with the
     * stack trace, and returns a correlation id the caller can quote.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleUnexpected(RuntimeException exception, HttpServletRequest request) {
        LOGGER.error("post-sales request FAILED unexpectedly: {} {} -- returning 500. correlationId={}",
            request.getMethod(), request.getRequestURI(), CorrelationIds.from(request), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            exception.getClass().getSimpleName() + ": " + exception.getMessage(), request, Map.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request, Map<String, ?> details) {
        return ResponseEntity.status(status).body(new ApiError(code, message, CorrelationIds.from(request), details));
    }

    private static String prefixed(String caseId) {
        return caseId == null || caseId.startsWith("psc-") ? caseId : "psc-" + caseId;
    }
}
