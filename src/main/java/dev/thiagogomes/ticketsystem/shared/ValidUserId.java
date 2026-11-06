package dev.thiagogomes.ticketsystem.shared;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The only accepted shape for {@code X-User-Id}. A strict allow-list keeps markup, control characters and
 * log-forging sequences out of the database, the logs and the responses that echo the id.
 */
@Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
@Constraint(validatedBy = {})
@Documented
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidUserId {

    String message() default "must be 1 to 64 characters: letters, digits, '-' or '_'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
