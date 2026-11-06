package team.projectpulse.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/** Parse once after authentication, outside authorization managers so malformed input is a 400, not a denial. */
public class StudentLifecycleIntentFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = StudentLifecycleIntentFilter.class.getName() + ".reactivate";
    private final PathPatternRequestMatcher matcher;
    private final CustomBearerTokenAuthenticationEntryPoint entryPoint;
    private final HandlerExceptionResolver resolver;

    public StudentLifecycleIntentFilter(String baseUrl, CustomBearerTokenAuthenticationEntryPoint entryPoint,
                                       HandlerExceptionResolver resolver) {
        this.matcher = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, baseUrl + "/students/{studentId}");
        this.entryPoint = entryPoint;
        this.resolver = resolver;
    }

    public static boolean reactivate(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!matcher.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            entryPoint.commence(request, response, new InsufficientAuthenticationException("Authentication required."));
            return;
        }
        String[] values = request.getParameterValues("reactivate");
        if (values != null && (values.length != 1 || !("true".equals(values[0]) || "false".equals(values[0])))) {
            resolver.resolveException(request, response, null,
                    new IllegalArgumentException("reactivate must be absent or one literal true or false."));
            return;
        }
        request.setAttribute(ATTRIBUTE, values != null && "true".equals(values[0]));
        chain.doFilter(request, response);
    }
}
