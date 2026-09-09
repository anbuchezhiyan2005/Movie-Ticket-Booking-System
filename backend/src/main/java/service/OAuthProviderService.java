package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import config.OAuthConfiguration;
import enums.AuthProvider;
import enums.OAuthIntent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.Date;
import java.util.logging.Logger;

@Singleton
public class OAuthProviderService {

    private static final Logger LOGGER = Logger.getLogger(OAuthProviderService.class.getName());

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OAuthStateService stateService;

    @Inject
    public OAuthProviderService(OAuthStateService stateService) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.objectMapper = new ObjectMapper();
        this.stateService = stateService;
    }

    public String authorizationUrl(jakarta.servlet.http.HttpServletRequest request, AuthProvider provider) {
        return authorizationUrl(request, provider, OAuthIntent.SIGN_IN, null);
    }

    public String authorizationUrl(jakarta.servlet.http.HttpServletRequest request, AuthProvider provider,
                                   OAuthIntent intent, Long userId) {
        OAuthConfiguration.Provider configuration = OAuthConfiguration.forProvider(provider);
        if (provider == AuthProvider.TWITTER) {
            LOGGER.info("OAuth authorization provider=TWITTER clientId="
                + maskClientId(configuration.clientId())
                + " redirectUri=" + configuration.redirectUri()
                + " scope=users.read pkce=true");
        }
        OAuthStateService.Transaction transaction = stateService.begin(
                request, provider, configuration.redirectUri(), intent, userId);

        String scope = provider == AuthProvider.GOOGLE ? "openid email profile" : "tweet.read users.read";
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", configuration.clientId());
        parameters.put("redirect_uri", configuration.redirectUri());
        parameters.put("response_type", "code");
        parameters.put("scope", scope);
        parameters.put("state", transaction.state());
        parameters.put("code_challenge", stateService.createCodeChallenge(transaction.codeVerifier()));
        parameters.put("code_challenge_method", "S256");
        if (provider == AuthProvider.GOOGLE) {
            parameters.put("access_type", "online");
            parameters.put("prompt", "select_account");
            parameters.put("nonce", transaction.nonce());
        }
        return configuration.authorizationEndpoint() + "?" + encode(parameters);
    }

    public CallbackResult callback(jakarta.servlet.http.HttpServletRequest request,
                                   AuthProvider expectedProvider, String code, String state) {
        OAuthStateService.Transaction transaction = stateService.consume(request, state);
        if (transaction.provider() != expectedProvider) {
            throw new IllegalArgumentException("OAuth provider does not match the transaction");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("OAuth authorization code is missing");
        }

        OAuthConfiguration.Provider configuration = OAuthConfiguration.forProvider(expectedProvider);
        if (expectedProvider == AuthProvider.TWITTER) {
            LOGGER.info("OAuth token exchange provider=TWITTER clientId="
                + maskClientId(configuration.clientId())
                + " redirectUri=" + transaction.redirectUri()
                + " codeVerifierPresent=" + (transaction.codeVerifier() != null)
                + " clientSecretPresent=" + (configuration.clientSecret() != null));
        }
        Map<String, String> tokenValues = form(
                "code", code,
                "redirect_uri", transaction.redirectUri(),
                "grant_type", "authorization_code",
            "code_verifier", transaction.codeVerifier());
        JsonNode token = postForm(configuration.tokenEndpoint(), tokenValues,
            expectedProvider == AuthProvider.TWITTER ? configuration : null);
        if (expectedProvider == AuthProvider.TWITTER) {
            LOGGER.info("Twitter token response"
                    + " token_type=" + redactedField(token, "token_type")
                    + " expires_in=" + redactedField(token, "expires_in")
                    + " scope=" + redactedField(token, "scope")
                    + " access_token=[REDACTED]"
                    + " refresh_token=[REDACTED]");
        }
        String accessToken = requiredText(token, "access_token");

        OAuthProfile profile = expectedProvider == AuthProvider.GOOGLE
            ? googleProfile(token, configuration, transaction.nonce())
                : twitterProfile(accessToken);
        return new CallbackResult(transaction, profile);
    }

        private OAuthProfile googleProfile(JsonNode token, OAuthConfiguration.Provider configuration,
                           String expectedNonce) {
        String idToken = requiredText(token, "id_token");
        JsonNode claims = verifyGoogleIdToken(idToken, configuration, expectedNonce);
        return new OAuthProfile(
                requiredText(claims, "sub"),
                text(claims, "email"),
                firstNonBlank(text(claims, "name"), text(claims, "email")));
    }

    private JsonNode verifyGoogleIdToken(String idToken, OAuthConfiguration.Provider configuration,
                                         String expectedNonce) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(idToken);
            if (!JWSAlgorithm.RS256.equals(signedJwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("Google token algorithm is invalid");
            }
            JsonNode jwks = getJson("https://www.googleapis.com/oauth2/v3/certs");
            JWKSet keySet = JWKSet.parse(jwks.toString());
            JWK key = keySet.getKeyByKeyId(signedJwt.getHeader().getKeyID());
            if (!(key instanceof RSAKey rsaKey)
                    || !signedJwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
                throw new IllegalArgumentException("Google token signature is invalid");
            }

            var jwtClaims = signedJwt.getJWTClaimsSet();
            String issuer = jwtClaims.getIssuer();
            if (!"https://accounts.google.com".equals(issuer)
                    && !"accounts.google.com".equals(issuer)) {
                throw new IllegalArgumentException("Google token issuer is invalid");
            }
            if (!jwtClaims.getAudience().contains(configuration.clientId())) {
                throw new IllegalArgumentException("Google token audience is invalid");
            }
            Date expiration = jwtClaims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                throw new IllegalArgumentException("Google token has expired");
            }
            Date issuedAt = jwtClaims.getIssueTime();
            long now = System.currentTimeMillis();
            if (issuedAt == null || issuedAt.after(new Date(now + 60_000))
                    || issuedAt.before(new Date(now - Duration.ofMinutes(10).toMillis()))) {
                throw new IllegalArgumentException("Google token issue time is invalid");
            }
            String authorizedParty = jwtClaims.getStringClaim("azp");
            if (authorizedParty != null && !configuration.clientId().equals(authorizedParty)) {
                throw new IllegalArgumentException("Google token authorized party is invalid");
            }
            String subject = jwtClaims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new IllegalArgumentException("Google token subject is missing");
            }
            JsonNode claims = new ObjectMapper().valueToTree(jwtClaims.getClaims());
            if (expectedNonce == null || !expectedNonce.equals(text(claims, "nonce"))) {
                throw new IllegalArgumentException("Google token nonce is invalid");
            }
            if (!"true".equalsIgnoreCase(text(claims, "email_verified"))) {
                throw new IllegalArgumentException("Google email is not verified");
            }
            return claims;
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException("Google token could not be verified", exception);
        }
    }

    private OAuthProfile twitterProfile(String accessToken) {
        JsonNode response = getJson("https://api.x.com/2/users/me?user.fields=name,username", accessToken);
        JsonNode data = response.path("data");
        return new OAuthProfile(
                requiredText(data, "id"),
                null,
                firstNonBlank(text(data, "name"), text(data, "username")));
    }

    private JsonNode postForm(String endpoint, Map<String, String> values,
                              OAuthConfiguration.Provider basicAuthProvider) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15));
        if (basicAuthProvider != null) {
            String credentials = basicAuthProvider.clientId() + ":" + basicAuthProvider.clientSecret();
            builder.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(encode(values)))
                .build();

        return send(request);
    }

    private JsonNode getJson(String endpoint) {
        return send(HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(15))
            .GET().build());
    }

    private JsonNode getJson(String endpoint, String accessToken) {
        return send(HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build());
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                LOGGER.warning("OAuth provider HTTP failure status=" + response.statusCode()
                        + " endpoint=" + request.uri()
                    + " error=" + safeProviderError(body)
                    + " body=" + boundedResponseBody(response.body()));
                throw new IllegalArgumentException("OAuth provider rejected the request");
            }
            return body;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OAuth provider request failed", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("OAuth provider request failed", exception);
        }
    }

    private Map<String, String> form(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index], values[index + 1]);
        }
        return result;
    }

    private String encode(Map<String, String> values) {
        StringJoiner query = new StringJoiner("&");
        values.forEach((key, value) -> query.add(encode(key) + "=" + encode(value)));
        return query.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OAuth provider response is missing " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private String redactedField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "[MISSING]" : value.asText();
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String maskClientId(String clientId) {
        if (clientId == null || clientId.length() < 8) {
            return "configured";
        }
        return clientId.substring(0, 4) + "..." + clientId.substring(clientId.length() - 4);
    }

    private String safeProviderError(JsonNode body) {
        String error = text(body, "error");
        String errorDescription = text(body, "error_description");
        if (error == null && errorDescription == null) {
            return "unspecified";
        }
        return (error == null ? "unspecified" : error)
                + (errorDescription == null ? "" : " (" + errorDescription + ")");
    }

    private String boundedResponseBody(String body) {
        if (body == null || body.isBlank()) {
            return "empty";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    public record OAuthProfile(String subject, String email, String displayName) {
    }

    public record CallbackResult(OAuthStateService.Transaction transaction, OAuthProfile profile) {
    }
}