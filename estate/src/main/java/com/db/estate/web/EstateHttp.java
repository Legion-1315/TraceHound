package com.db.estate.web;

import com.db.estate.ops.ServiceIdentity;
import org.slf4j.MDC;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/** Builds RestClients for service-to-service calls: timeouts, correlation id and caller identity on every request. */
@Component
public class EstateHttp {

    private final RestClient.Builder builder;
    private final ServiceIdentity identity;

    public EstateHttp(RestClient.Builder builder, ServiceIdentity identity) {
        this.builder = builder;
        this.identity = identity;
    }

    public RestClient client(String baseUrl) {
        HttpClient jdk = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String corr = MDC.get("corr");
                    if (corr != null) {
                        request.getHeaders().set(CorrelationFilter.HEADER, corr);
                    }
                    request.getHeaders().set("X-Caller", identity.serviceId());
                    return execution.execute(request, body);
                })
                .build();
    }

    /** One-line description of a failed call, as an engineer would want it in a log. */
    public static String brief(RestClientException e) {
        if (e instanceof HttpStatusCodeException h) {
            return "HTTP " + h.getStatusCode().value();
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
