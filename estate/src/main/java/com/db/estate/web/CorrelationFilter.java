package com.db.estate.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Carries the X-Correlation-Id of an inbound call into the logging MDC, so one trade can be traced across services. */
@Component
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String corr = request.getHeader(HEADER);
        if (corr != null && !corr.isBlank()) {
            MDC.put("corr", corr);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("corr");
        }
    }
}
