package com.db.triage.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Serves the bundled React app (folded into classpath:/static/ by the Gradle
 * {@code buildFrontend} task) from the same process and port as the API.
 *
 * <p>Anything that isn't a real static file falls back to index.html so the UI
 * survives a refresh on any path. {@code /api/**} and {@code /ws} are excluded
 * so they keep returning JSON / upgrading to a WebSocket rather than HTML.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    private static final ClassPathResource INDEX = new ClassPathResource("static/index.html");

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // Let the API and the WebSocket endpoint 404 / upgrade normally,
                        // and don't pretend to serve a UI that wasn't bundled (-PskipFrontend).
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws") || !INDEX.exists()) {
                            return null;
                        }
                        return INDEX;
                    }
                });
    }
}
