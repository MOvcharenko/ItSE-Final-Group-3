-- Local sign-in only (profile "local"): everyone who can sign in. Passwords are stored only encoded ({bcrypt}...).
-- Kept apart from app_user, which holds scheduling profiles: signin_account.id is the token's sub and app_user's id.
-- A scheduler's departments are scheduler_department rows and an instructor's home department is app_user.department,
-- the same rows the API reads, so they are not repeated here. Issued codes and tokens are kept in memory.
CREATE TABLE signin_account (
    id         VARCHAR(100) PRIMARY KEY,
    username   VARCHAR(100) NOT NULL,
    password   VARCHAR(200) NOT NULL,
    name       VARCHAR(200) NOT NULL,
    email      VARCHAR(200),
    role       VARCHAR(20)  NOT NULL,
    admin      BOOLEAN      NOT NULL DEFAULT FALSE,    -- may manage accounts
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_signin_account_username UNIQUE (username),
    CONSTRAINT ck_signin_account_role CHECK (role IN ('SCHEDULER', 'INSTRUCTOR', 'STUDENT')),
    CONSTRAINT ck_signin_account_password_encoded CHECK (password LIKE '{%}%')
);
