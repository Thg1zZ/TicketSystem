package dev.thiagogomes.ticketsystem.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every error leaves the API as RFC 9457 ProblemDetail, never with stack traces or SQL.
 * Framework errors (400, 404, 405...) are handled by the parent class.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Conflict", ex.getMessage());
        ex.details().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(PaymentRequiredException.class)
    ProblemDetail handlePaymentRequired(PaymentRequiredException ex) {
        ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED, "Payment not approved", ex.getMessage());
        ex.details().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUnavailable(ServiceUnavailableException ex) {
        log.warn("Dependency unavailable: {}", ex.getMessage(), ex.getCause());
        return unavailable(ex.getMessage());
    }

    /**
     * Database down or connection pool exhausted: a temporary condition, not a bug (503, not 500). Decided by the
     * whole cause chain, because the failure that surfaces is often a secondary one: when the connection dies
     * mid-query, the rollback fails too and its exception replaces the original.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<ProblemDetail> handleDataAccess(RuntimeException ex) {
        if (!isConnectivityFailure(ex)) {
            return ResponseEntity.internalServerError().body(handleUnexpected(ex));
        }
        log.warn("Infrastructure unavailable: {}", ex.getMessage());
        return unavailable("A required service is temporarily unavailable; retry later");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Data conflict", "The request conflicts with the current state of the resource");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return handleExceptionInternal(ex, withErrors(ex.getBody(), errors), headers, status, request);
    }

    /**
     * Raised instead of the above when the handler also validates headers/path variables.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError
                        ? fieldError.getField()
                        : result.getMethodParameter().getParameterName();
                errors.putIfAbsent(field, error.getDefaultMessage());
            }
        }
        return handleExceptionInternal(ex, withErrors(ex.getBody(), errors), headers, status, request);
    }

    private static ProblemDetail withErrors(ProblemDetail body, Map<String, String> errors) {
        body.setDetail("Request has invalid fields");
        body.setProperty("errors", errors);
        return body;
    }

    /**
     * SQLState class 08 is the standard "connection exception". A rollback or commit failing at the JDBC level
     * (Hibernate TransactionException) also means the connection is gone: on a live one it practically never fails.
     */
    static boolean isConnectivityFailure(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof CannotCreateTransactionException
                    || cause instanceof org.hibernate.TransactionException
                    || (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))) {
                return true;
            }
        }
        return false;
    }

    private static ResponseEntity<ProblemDetail> unavailable(String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", detail));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
