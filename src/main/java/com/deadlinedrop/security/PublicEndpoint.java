package com.deadlinedrop.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Method-level marker annotation for deliberately public endpoints.
 * Architecture rules enforce that every public controller method carries
 * either {@code @PreAuthorize} or {@code @PublicEndpoint}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
}
