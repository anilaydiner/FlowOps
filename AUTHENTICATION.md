# Authentication sessions

FlowOps issues short-lived JWT access tokens and rotating opaque refresh tokens.
Authentication orchestration, token contracts and session settings live under
`application.identity`; JWT signing/verification, JDBC storage and cleanup scheduling
are infrastructure adapters. Authentication mechanisms introduce no domain dependencies
on JWT, refresh-token storage or HTTP.

## Configuration

```yaml
flowops:
  jwt:
    expiration-seconds: 900
  auth:
    refresh-token-lifetime: 7d
    maximum-session-lifetime: 30d
```

| Setting | Environment variable | Default |
|---------|----------------------|---------|
| Access-token lifetime in seconds | `FLOWOPS_JWT_EXPIRY` | 900 (15 minutes) |
| Refresh-token lifetime | `FLOWOPS_REFRESH_TOKEN_LIFETIME` | 7d |
| Maximum session lifetime | `FLOWOPS_MAXIMUM_SESSION_LIFETIME` | 30d |

Duration settings accept Spring duration values such as `30m`, `12h`, `7d` or ISO-8601
`P7D`. Lifetimes must be positive, and the maximum session lifetime must be at least
the refresh lifetime. The existing JWT secret configuration is unchanged.

Each successful refresh gets a new refresh deadline, capped at the original session
expiry. Rotation never extends that original deadline. Access tokens are also capped
at the session expiry. Expiry boundaries are exclusive: a token/session is invalid
at its expiration instant.

## Login

`POST /api/v1/auth/token`

```json
{"username":"alice","password":"..."}
```

The response preserves the original `token`, `tokenType` and `expiresIn` fields:

```json
{
  "token": "<JWT access token>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "<opaque refresh token>",
  "refreshExpiresIn": 604800,
  "sessionExpiresAt": "<UTC timestamp>"
}
```

`expiresIn` and `refreshExpiresIn` are seconds. Credential responses include
`Cache-Control: no-store` and `Pragma: no-cache`. Tokens are returned as JSON, not
cookies. Clients must protect their stored bearer credentials and replace the stored
refresh token after each successful refresh.

Each login creates a separate session, allowing independent devices to sign out.
No raw refresh token is stored: tokens contain 256 random bits and the database
stores only their SHA-256 hashes. Session credential fingerprints identify password
changes without duplicating the user's stored password hash.

## Refresh

`POST /api/v1/auth/refresh`, without requiring a valid access token:

```json
{"refreshToken":"<latest refresh token>"}
```

A successful call returns the same response shape with a new access token and a new
refresh token. User status and current roles are reloaded before issuance. Malformed
bodies return 400; unknown, expired, revoked or already-consumed refresh credentials
return a uniform 401 without reflecting the token.

Session-row locks serialize rotation and logout. Token state is re-read after
acquiring the session lock. Replaying a consumed refresh token revokes the entire
refresh session, including its newly rotated refresh token. Already-issued access
JWTs remain valid until expiry. Refresh revocation commits even
though the refresh endpoint returns 401. Clients must serialize refresh calls per
session: parallel use of the same token is treated as replay. Retrying a refresh
whose successful response was lost also triggers reauthentication rather than
silently accepting reuse.

This rotation and family-revocation design follows the refresh-token replay guidance
in [RFC 9700 section 4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14).
These are FlowOps JSON authentication endpoints, not a complete OAuth authorization server.

## Logout

`POST /api/v1/auth/logout` supports either:

- The current `Authorization: Bearer <access token>` header, with no body.
- A JSON `refreshToken` body, including when the access token has expired.

With an authenticated bearer token, the refresh session identified by its signed
session id is revoked; an accompanying
refresh body is ignored. Without a valid bearer session, the supplied refresh token
selects the session. A previously consumed refresh token can still revoke its family.
Unknown tokens, already-revoked sessions and anonymous requests return 204 as no-ops.
Missing/blank refresh fields in a supplied JSON body still fail input validation.

`POST /api/v1/auth/logout-all` requires a valid bearer token and revokes all refresh sessions
belonging to that authenticated user. Other users are unaffected. Successful logout
responses have no body and return 204.

Access JWTs are validated locally by signature, issuer, required claims and expiration.
There is no session or password-fingerprint database lookup during token authentication.
Business authorization may still query current user permissions as before.

Logout immediately prevents further refreshes; already-issued access tokens remain valid
until their short expiration (15 minutes by default). The same bounded validity window
applies after refresh-token replay, password changes/resets and account disabling. These
checks block refresh/issuance rather than revoking JWTs. Absolute session expiry still
caps JWT expiration when tokens are issued. Refresh uses current role assignments;
existing access-token role claims retain their value until refresh/expiry.

Clients should discard both local credentials on logout and stop sending their old
access token. A caller retaining an unexpired JWT can continue authenticated requests
until it expires. Choose the access-token lifetime to match that intended window.

## Persistence and rollout

Flyway V15 creates `authentication_sessions` and `authentication_refresh_tokens` with
foreign keys and indexes. Password/user changes remain in the existing user storage.
Session/token writes and refresh rotation use the same Spring-managed datasource
transaction as application operations. Signing/issuance failures roll back rotation.
Expired sessions and their refresh hashes are removed hourly through cascade deletion.
Consumed hashes are retained until session expiry so replay remains detectable.

JWTs now contain an issuer, unique token id and session id. Previously issued JWTs
without these session claims are rejected after rollout; existing clients must log in
again. The API still uses bearer headers and does not create servlet HTTP sessions,
and JWT authentication is stateless. Refresh and logout use database-backed token
families. The default
access-token lifetime changes from 8 hours to 15 minutes; explicit configured values
remain supported.

Tests use a controllable clock to cover expiry boundaries without sleeps, absolute
expiry caps, token rotation and replay, concurrent refresh, current/all-session refresh
revocation, natural access expiry, credential changes, disabled users, fresh role claims, hash-only persistence
and cleanup. Existing JWT redaction and application integration tests remain in place.
