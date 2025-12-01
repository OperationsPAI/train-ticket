package edu.fudan.common.config;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * RestTemplate interceptor that automatically propagates JWT Authorization header
 * from incoming requests to outgoing service-to-service calls.
 * 
 * This interceptor:
 * 1. Retrieves the current HTTP request from RequestContextHolder
 * 2. Extracts the Authorization header (JWT token)
 * 3. Adds it to outgoing RestTemplate requests
 * 
 * Note: This also prevents Content-Length mismatch issues that occur in Spring Boot 3.x
 * when forwarding requests, as we don't copy the original Content-Length header.
 * 
 * @author fdse
 */
public class JwtInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, 
            ClientHttpRequestExecution execution) throws IOException {
        
        // Only set Authorization header if it's not already present in the request
        // This allows HttpEntity headers to take precedence
        if (!request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            // Try to get Authorization from the current servlet request context
            RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
            
            if (requestAttributes instanceof ServletRequestAttributes) {
                HttpServletRequest servletRequest = ((ServletRequestAttributes) requestAttributes).getRequest();
                String authorization = servletRequest.getHeader(HttpHeaders.AUTHORIZATION);
                
                if (authorization != null && !authorization.isEmpty()) {
                    request.getHeaders().set(HttpHeaders.AUTHORIZATION, authorization);
                }
            }
        }
        
        // Remove headers that can cause issues in Spring Boot 3.x when forwarding requests
        // Content-Length: causes "too many bytes written" error when body size differs
        // Transfer-Encoding: can conflict with Content-Length
        // Host: should be set by the HTTP client for the target service
        request.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        request.getHeaders().remove(HttpHeaders.TRANSFER_ENCODING);
        request.getHeaders().remove(HttpHeaders.HOST);
        
        // Ensure Content-Type is set for requests with body
        if (body != null && body.length > 0 && !request.getHeaders().containsKey(HttpHeaders.CONTENT_TYPE)) {
            request.getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        }
        
        return execution.execute(request, body);
    }
}
