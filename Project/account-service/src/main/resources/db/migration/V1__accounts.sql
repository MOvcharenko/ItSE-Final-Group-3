-- Everyone who can sign in. Passwords are stored only encoded ({bcrypt}...). The id becomes the token's sub, which the
-- Academic Scheduling module keys its own records on. Issued codes and tokens are kept in memory.
CREATE TABLE signin_account (
    id               VARCHAR(100) PRIMARY KEY,
    username         VARCHAR(100) NOT NULL,
    password         VARCHAR(200) NOT NULL,
    name             VARCHAR(200) NOT NULL,
    email            VARCHAR(200),
    role             VARCHAR(20)  NOT NULL,
    home_department  VARCHAR(100),                       -- an instructor's; the token's "department" claim
    admin            BOOLEAN      NOT NULL DEFAULT FALSE,    -- may manage accounts
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_signin_account_username UNIQUE (username),
    CONSTRAINT ck_signin_account_role CHECK (role IN ('SCHEDULER', 'INSTRUCTOR', 'STUDENT')),
    CONSTRAINT ck_signin_account_password_encoded CHECK (password LIKE '{%}%')
);

-- The departments a scheduler manages: the token's "departments" claim.
CREATE TABLE account_department (
    account_id  VARCHAR(100) NOT NULL REFERENCES signin_account (id) ON DELETE CASCADE,
    department  VARCHAR(100) NOT NULL,
    PRIMARY KEY (account_id, department)
);
