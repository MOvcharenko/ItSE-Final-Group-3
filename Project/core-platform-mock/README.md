# Core Platform — mock

A stand-in for the Core Platform so the Academic Scheduling module can be
demonstrated end-to-end with real OIDC sign-in, without the real thing.

**This is a mock.** It issues signed JWTs to three hardcoded accounts, has no
password storage, no user management, and no administrator functions. Those
belong to the real Core Platform (TR3.1, TR4.2).

## Run

    cd Project/core-platform-mock
    ./mvnw spring-boot:run

Listens on <http://localhost:9000>.

## Sign in through it

    cd Project/scheduling
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=mock

Then open <http://localhost:8080/> and use **Sign in with Core Platform**.

The mock's own sign-in page lists the three test accounts from the doc:
`U01` Maya Brooks (Scheduler, CS + MATH), `U02` L. Bianchi (Instructor),
`U03` Jordan Ellis (Student).

## Endpoints

| Path | Purpose |
|---|---|
| `GET /.well-known/openid-configuration` | OIDC discovery. This is what `issuer-uri` resolves to. |
| `GET /.well-known/jwks.json` | The mock's RSA public key, so JWTs validate without sharing a secret. |
| `GET /oauth2/authorize?…` | Sign-in page with the three test accounts. |
| `POST /oauth2/authorize` | Completes authorization: 302 to `redirect_uri?code=&state=`. |
| `POST /oauth2/token` | Authorization-code exchange; validates the PKCE S256 challenge. |

Supported: `authorization_code` grant, `S256` PKCE, `RS256`. Nothing else.

## Switching to the real Core Platform

Point the scheduling module's `spring.security.oauth2.resourceserver.jwt.issuer-uri`
at the real issuer URL and drop the `mock` profile. Nothing else in the
scheduling module needs to change: the browser flow, the JWKS refetch, and the
`role`/`name`/`sub` claims are the same.

The real Core Platform's claim names are still to be confirmed
(`SecurityConfig.ROLE_CLAIM` / `NAME_CLAIM`); if they differ, change those two
constants and this mock's `TokenService` to match.

## What it does not do

- No passwords, no user management, no admin — those are the real Core Platform.
- The signing key is generated at startup, so restarting the mock invalidates
  every token issued before. This matches the existing `/dev/token` behaviour.
- One process, in-memory only. No persistence of auth codes across restarts.
- Test/demo only. Never run this in production.

## How to run demo

# terminal 1 — the mock Core Platform
cd Project/core-platform-mock
./mvnw spring-boot:run

# terminal 2 — the scheduling module in the mock profile
cd Project/scheduling
./mvnw spring-boot:run -Dspring-boot.run.profiles=mock

# browser
open http://localhost:8080/