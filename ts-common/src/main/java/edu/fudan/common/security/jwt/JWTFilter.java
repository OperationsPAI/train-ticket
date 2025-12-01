package edu.fudan.common.security.jwt;

import edu.fudan.common.exception.TokenException;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * JWT Authentication Filter
 * 
 * This filter extracts and validates JWT tokens from incoming requests.
 * It sets the authentication in SecurityContext if a valid token is present.
 * 
 * For permitAll() endpoints, requests without tokens or with invalid tokens
 * will still be allowed to proceed - authorization is handled by Spring Security.
 * 
 * @author fdse
 */
public class JWTFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JWTFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, FilterChain filterChain) throws ServletException, IOException {

        try {
            Authentication authentication = JWTUtil.getJWTAuthentication(httpServletRequest);
            if (authentication != null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                LOGGER.debug("[JWTFilter][doFilterInternal][Token validated for user: {}]", authentication.getName());
            }
            // Always continue the filter chain - let Spring Security handle authorization
            // This allows permitAll() endpoints to work without authentication
        } catch (JwtException | TokenException e) {
            // Log the error but don't block the request
            // Spring Security will deny access if authentication is required
            LOGGER.debug("[JWTFilter][doFilterInternal][Token validation failed: {}]", e.getMessage());
            // Clear any partial authentication
            SecurityContextHolder.clearContext();
        }
        
        // Always continue the filter chain
        // This is critical for permitAll() endpoints to work correctly
        filterChain.doFilter(httpServletRequest, httpServletResponse);
    }
}
