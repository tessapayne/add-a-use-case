package team.projectpulse.security;

import org.springframework.security.authentication.AuthenticationServiceException;

public class AccountStoreUnavailableException extends AuthenticationServiceException {
    public AccountStoreUnavailableException() {
        super("Account verification is temporarily unavailable. Please try again.");
    }
}
