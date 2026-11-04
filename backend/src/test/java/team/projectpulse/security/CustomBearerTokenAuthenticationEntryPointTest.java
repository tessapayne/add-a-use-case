package team.projectpulse.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomBearerTokenAuthenticationEntryPointTest {
    @Test
    void accountStoreFailureMapsToRetryableInfrastructureException() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        var validation = new JwtValidationException("validation failed", List.of(
                new OAuth2Error(ActiveAccountValidator.ACCOUNT_STORE_UNAVAILABLE)));
        var entryPoint = new CustomBearerTokenAuthenticationEntryPoint(resolver);
        entryPoint.commence(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new InvalidBearerTokenException("invalid", validation));
        verify(resolver).resolveException(any(), any(), isNull(), isA(AccountStoreUnavailableException.class));
    }

    @Test
    void invalidTokenKeepsExistingAuthenticationErrorMapping() throws Exception {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        var exception = new InvalidBearerTokenException("invalid signature");
        new CustomBearerTokenAuthenticationEntryPoint(resolver).commence(
                new MockHttpServletRequest(), new MockHttpServletResponse(), exception);
        verify(resolver).resolveException(any(), any(), isNull(), same(exception));
    }
}
