package config;

import enums.AuthProvider;

public final class OAuthConfiguration {

    private OAuthConfiguration() {
    }

    public static Provider google() {
        return provider(
                AuthProvider.GOOGLE,
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                "GOOGLE_REDIRECT_URI",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token");
    }

    public static Provider twitter() {
        return provider(
                AuthProvider.TWITTER,
                "TWITTER_CLIENT_ID",
                "TWITTER_CLIENT_SECRET",
                "TWITTER_REDIRECT_URI",
                "https://x.com/i/oauth2/authorize",
                "https://api.x.com/2/oauth2/token");
    }

    public static Provider forProvider(AuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> google();
            case TWITTER -> twitter();
        };
    }

    public static String successRedirectUri() {
        return required("OAUTH_SUCCESS_REDIRECT_URI");
    }

    public static String errorRedirectUri() {
        return required("OAUTH_ERROR_REDIRECT_URI");
    }

    private static Provider provider(AuthProvider provider, String clientIdKey, String clientSecretKey,
                                     String redirectUriKey, String authorizationEndpoint,
                                     String tokenEndpoint) {
        return new Provider(
                provider,
                required(clientIdKey),
                required(clientSecretKey),
                required(redirectUriKey),
                authorizationEndpoint,
                tokenEndpoint);
    }

    private static String required(String key) {
        String value = EnvironmentConfig.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is not configured");
        }
        return value;
    }

    public record Provider(AuthProvider provider, String clientId, String clientSecret,
                           String redirectUri, String authorizationEndpoint,
                           String tokenEndpoint) {
    }
}