package team.projectpulse.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.security.oauth2.jwt.Jwt;
import team.projectpulse.student.Student;
import team.projectpulse.user.UserRepository;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student T-jwt / FR-SEC-active-account. Wiring and real-token tests follow separately. */
@ExtendWith(MockitoExtension.class)
class ActiveAccountValidatorTest {
    @Mock UserRepository users;
    ActiveAccountValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ActiveAccountValidator(users);
    }

    static Stream<Object> invalidIds() {
        return Stream.of(null, "1", 1.5, true, List.of(1), 0, -1,
                2147483648L, new BigInteger("99999999999999999999999"));
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void malformedIdentityDoesNotLookupAccount(Object id) {
        assertThat(validator.validate(token(id, Instant.now().plusSeconds(300))).hasErrors()).isTrue();
        verifyNoInteractions(users);
    }

    @Test
    void expiredTokenKeepsDefaultValidationAndDoesNotLookupAccount() {
        assertThat(validator.validate(token(1, Instant.now().minusSeconds(300))).hasErrors()).isTrue();
        verifyNoInteractions(users);
    }

    @Test
    void explicitNullIdentityDoesNotLookupAccount() {
        Jwt token = Jwt.withTokenValue("test").header("alg", "RS256").subject("no-fallback")
                .claims(claims -> claims.put("userId", null)).build();
        assertThat(validator.validate(token).hasErrors()).isTrue();
        verifyNoInteractions(users);
    }

    @Test
    void accountStateIsReadAgainAfterDeactivationAndRestoration() {
        Student account = new Student();
        account.setEnabled(true);
        given(users.findById(1)).willReturn(Optional.of(account));
        Jwt token = token(1L, Instant.now().plusSeconds(300));
        assertThat(validator.validate(token).hasErrors()).isFalse();
        account.setEnabled(false);
        assertThat(validator.validate(token).hasErrors()).isTrue();
        account.setEnabled(true);
        assertThat(validator.validate(token).hasErrors()).isFalse();
        verify(users, times(3)).findById(1);
    }

    @Test
    void absentAccountRefused() {
        assertThat(validator.validate(token(Integer.MAX_VALUE, Instant.now().plusSeconds(300))).hasErrors()).isTrue();
        verify(users).findById(Integer.MAX_VALUE);
    }

    static Stream<RuntimeException> infrastructureFailures() {
        return Stream.of(new DataAccessResourceFailureException("SECRET database address"),
                new CannotCreateTransactionException("SECRET transaction-start details"),
                new TransactionSystemException("SECRET transaction infrastructure"),
                new TransactionTimedOutException("SECRET transaction timeout"));
    }

    @ParameterizedTest
    @MethodSource("infrastructureFailures")
    void outageProducesDistinctGenericError(RuntimeException failure) {
        given(users.findById(1)).willThrow(failure);
        var result = validator.validate(token(1, Instant.now().plusSeconds(300)));
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getErrorCode()).isEqualTo(ActiveAccountValidator.ACCOUNT_STORE_UNAVAILABLE);
            assertThat(error.getDescription()).isEqualTo("Account verification is temporarily unavailable. Please try again.");
        });
    }

    private Jwt token(Object id, Instant expiry) {
        var builder = Jwt.withTokenValue("test").header("alg", "RS256")
                .issuedAt(expiry.minusSeconds(3600)).expiresAt(expiry).subject("no-identity-fallback");
        if (id != null) builder.claim("userId", id);
        return builder.build();
    }
}
