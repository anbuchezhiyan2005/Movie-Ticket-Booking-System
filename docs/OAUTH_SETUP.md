# OAuth Setup

The customer sign-in buttons use server-side OAuth 2.0 Authorization Code with PKCE. Provider tokens are exchanged by the backend and are never stored in browser storage. Google ID tokens are verified locally against Google's JWKS before claims are used.

Configure these values as environment variables, JVM properties, or `.env` values:

```text
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REDIRECT_URI=https://your-host/movie-booking/api/auth/google/callback
TWITTER_CLIENT_ID=
TWITTER_CLIENT_SECRET=
TWITTER_REDIRECT_URI=https://your-host/movie-booking/api/auth/twitter/callback
OAUTH_SUCCESS_REDIRECT_URI=https://your-host/movie-booking/
OAUTH_ERROR_REDIRECT_URI=https://your-host/movie-booking/
```

Register the exact Google and Twitter/X callback URLs with each provider. Use HTTPS in deployed environments. `SameSite=Lax` is required so the Tomcat session containing the short-lived OAuth state and PKCE verifier survives the provider's top-level callback redirect.

Google sign-in requires the `openid email profile` scopes and a verified email. Twitter/X sign-in uses the stable provider user ID and does not require an email address.

Authenticated customers can explicitly link an additional provider through:

```text
GET /api/auth/google/link/start
GET /api/auth/google/link/callback
GET /api/auth/twitter/link/start
GET /api/auth/twitter/link/callback
```

Linking is bound to the current customer session. Matching emails are never merged automatically.

Social sign-in creates customer accounts only. A provider email matching an existing password account is rejected until explicit account linking is implemented.