package com.ielop.platform.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Intercepts incoming HTTP requests to establish a consistent correlation/request ID.
 *
 * <p>Extracts {@code X-Request-ID} or {@code X-Correlation-ID} from headers if present,
 * otherwise generates a new UUID. Populates SLF4J {@link MDC} and sets the
 * {@code X-Request-ID} header on the HTTP response.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    public static String getCurrentRequestId() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : UUID.randomUUID().toString();
    }

    private String resolveRequestId(HttpServletRequest request) {
        String headerValue = request.getHeader(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(headerValue)) {
            headerValue = request.getHeader(CORRELATION_ID_HEADER);
        }
        if (StringUtils.hasText(headerValue) && isValidRequestId(headerValue.trim())) {
            return headerValue.trim();
        }
        return UUID.randomUUID().toString();
    }

    private boolean isValidRequestId(String value) {
        // Sanitize header value to prevent log injection or header manipulation
        return value.length() <= 128 && value.matches("^[a-zA-Z0-9_-]+$");
    }
}
