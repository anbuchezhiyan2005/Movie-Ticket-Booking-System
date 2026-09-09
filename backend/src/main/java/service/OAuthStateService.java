package service;

import enums.AuthProvider;
import enums.OAuthIntent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class OAuthStateService {

    private static final String SESSION_ATTRIBUTE = OAuthStateService.class.getName() + ".transactions";
    private static final Duration TRANSACTION_LIFETIME = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    public Transaction begin(HttpServletRequest request, AuthProvider provider, String redirectUri) {
        return begin(request, provider, redirectUri, OAuthIntent.SIGN_IN, null);
    }

    public Transaction begin(HttpServletRequest request, AuthProvider provider, String redirectUri,
                             OAuthIntent intent, Long userId) {
        if (request == null || provider == null || redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("OAuth transaction details are required");
        }

        HttpSession existingSession = request.getSession(false);
        if (existingSession != null) {
            request.changeSessionId();
        }

        Transaction transaction = new Transaction(
                provider,
                intent,
                userId,
                randomToken(32),
                randomToken(32),
                randomToken(32),
                redirectUri,
                Instant.now());
        HttpSession session = request.getSession(true);
        Map<String, Transaction> transactions = transactions(session);
        transactions.entrySet().removeIf(entry -> isExpired(entry.getValue()));
        transactions.put(transaction.state(), transaction);
        return transaction;
    }

    public Transaction consume(HttpServletRequest request, String state) {
        HttpSession session = request == null ? null : request.getSession(false);
        Map<String, Transaction> transactions = session == null ? null : transactions(session);
        Transaction transaction = transactions == null ? null : transactions.remove(state);
        if (transaction == null) {
            throw new IllegalArgumentException("OAuth transaction is missing");
        }

        if (state == null || !constantTimeEquals(transaction.state(), state)) {
            throw new IllegalArgumentException("OAuth state is invalid");
        }
        if (isExpired(transaction)) {
            throw new IllegalArgumentException("OAuth transaction has expired");
        }
        return transaction;
    }

    public String createCodeChallenge(String codeVerifier) {
        if (codeVerifier == null || codeVerifier.isBlank()) {
            throw new IllegalArgumentException("PKCE code verifier is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Transaction> transactions(HttpSession session) {
        Object value = session.getAttribute(SESSION_ATTRIBUTE);
        if (value instanceof Map<?, ?>) {
            return (Map<String, Transaction>) value;
        }
        Map<String, Transaction> transactions = new HashMap<>();
        session.setAttribute(SESSION_ATTRIBUTE, transactions);
        return transactions;
    }

    private boolean isExpired(Transaction transaction) {
        return Instant.now().isAfter(transaction.createdAt().plus(TRANSACTION_LIFETIME));
    }

    public record Transaction(AuthProvider provider, OAuthIntent intent, Long userId,
                              String state, String codeVerifier, String nonce, String redirectUri,
                              Instant createdAt) {
    }
}