package dev.thiagogomes.ticketsystem.shared.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

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
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", ex.getMessage()));
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

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
