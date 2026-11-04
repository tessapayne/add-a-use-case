package team.projectpulse.security;

import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.stereotype.Component;
import team.projectpulse.user.UserRepository;

import java.math.BigDecimal;

/** Signature verification stays in the decoder; defaults and strict identity precede account lookup. */
@Component
public class ActiveAccountValidator implements OAuth2TokenValidator<Jwt> {

    public static final String ACCOUNT_STORE_UNAVAILABLE = "account_store_unavailable";
    private final UserRepository userRepository;
    private final OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefault();

    public ActiveAccountValidator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        OAuth2TokenValidatorResult defaultResult = this.defaults.validate(token);
        if (defaultResult.hasErrors()) {
            return defaultResult;
        }
        Integer userId = readUserId(token.getClaim("userId"));
        if (userId == null) {
            return invalidAccount();
        }
        try {
            return this.userRepository.findById(userId).filter(user -> user.isEnabled()).isPresent()
                    ? OAuth2TokenValidatorResult.success() : invalidAccount();
        } catch (DataAccessException ex) {
            // A validator reports an OAuth error; the entry point maps this one to a generic retryable 503.
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(ACCOUNT_STORE_UNAVAILABLE,
                    "Account verification is temporarily unavailable. Please try again.", null));
        }
    }

    static Integer readUserId(Object claim) {
        if (!(claim instanceof Number number)) {
            return null;
        }
        try {
            int id = new BigDecimal(number.toString()).intValueExact();
            return id > 0 ? id : null;
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    private OAuth2TokenValidatorResult invalidAccount() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid account.", null));
    }
}
