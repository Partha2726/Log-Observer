package com.ielop.platform.trace.assembly;

import com.ielop.platform.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** The seed query found no line carrying the flowRef (404 {@code TRACE_NOT_FOUND}, F-10). */
public class TraceNotFoundException extends ApiException {

    public static final String CODE = "TRACE_NOT_FOUND";

    public TraceNotFoundException(String flowRef) {
        super(CODE, HttpStatus.NOT_FOUND, "No logs for this reference in the selected range: " + flowRef);
    }
}
