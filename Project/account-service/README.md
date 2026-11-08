# Account service (stand-in Core Platform)

Accounts, passwords and sign-in for the Academic Scheduling module, until the real Core Platform exists.

**This is not part of the scheduling module.** Phase 1 leaves sign-in and account management (users, roles,
passwords) to the Core Platform, and the module "shall not implement its own separate login". So this runs as its own
app, with its own database, and the scheduling module only checks the tokens it issues, exactly as it would the real
Core Platform's. The scheduling module also runs without it: by default it offers three test accounts and no passwords.

## Run

Start this first, then the scheduling module with the `accounts` profile:

```sh
cd Project/account-service && ./mvnw spring-boot:run                                    # http://localhost:9000
cd Project/scheduling && ./mvnw spring-boot:run -Dspring-boot.run.profiles=accounts      # http://localhost:8080
```

On Windows use `.\mvnw.cmd`, and quote the profile: `'-Dspring-boot.run.profiles=accounts'`.

Open `http://localhost:8080/`, which sends you here to sign in:

| Username | Password | Role |
|---|---|---|
| `m.brooks` | `Scheduler#2026` | Scheduler (CS, MATH), and account administrator |
| `l.bianchi` | `Instructor#2026` | Instructor (home department MATH) |
| `j.ellis` | `Student#2026` | Student |

These are demo passwords, created on first start. `http://localhost:9000/` shows your account and lets you change your
password; an administrator also creates accounts, resets passwords and disables accounts there. Accounts and the
token-signing key are kept in `./data` (git-ignored); delete it to start over.

## What it gives the scheduling module

An OpenID Connect provider (Spring Authorization Server): the authorization-code flow with PKCE for one public client,
the scheduling UI. Its discovery document is at `http://localhost:9000/.well-known/openid-configuration`.

Access tokens are JWTs signed with this app's key, for audience `scheduling-api`, with these claims:

| Claim | Value |
|---|---|
| `sub` | the account id (`U01`, ...), the user id the scheduling module stores |
| `name`, `email` | from the account |
| `role` | `STUDENT`, `INSTRUCTOR` or `SCHEDULER` |
| `departments` | a scheduler's departments, e.g. `["CS", "MATH"]` |
| `department` | an instructor's home department |

A sign-in lasts 1 hour; the scheduling UI then signs in again, straight through while this app's session lasts.
Disabling an account stops it signing in at once, and a token it already holds stops working when it expires.

## Settings

In `src/main/resources/application.yml`, under `accounts`:

| Setting | Default | Meaning |
|---|---|---|
| `issuer` | `http://localhost:9000` | this app's address; the scheduling module's `issuer-uri` |
| `app-urls` | `http://localhost:8080`, `http://127.0.0.1:8080` | where the scheduling UI runs: sign-in and sign-out return there, and its token request is allowed from there. To run it on another port, add that address |
| `client-id`, `scope` | `scheduling-web`, `openid profile email` | must match the scheduling module's `scheduling.auth.*` |
| `api-audience` | `scheduling-api` | must match its `spring.security.oauth2.resourceserver.jwt.audiences` |
| `access-token-ttl` | `1h` | how long a sign-in lasts |
| `seed-accounts` | the three above | created on first start only |

## Test

```sh
./mvnw verify
```

`SignInIT` runs the browser's sign-in flow, checks the tokens' signature and claims, and covers the account pages.
