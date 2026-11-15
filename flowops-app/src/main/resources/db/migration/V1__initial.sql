-- FlowOps Iteration 1: departments + users (seed)
CREATE TABLE departments (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            BIGSERIAL   PRIMARY KEY,
    username      VARCHAR(64) NOT NULL UNIQUE,
    display_name  VARCHAR(100) NOT NULL,
    email         VARCHAR(255) UNIQUE,
    department_id BIGINT REFERENCES departments(id),
    role          VARCHAR(16)  NOT NULL DEFAULT 'MEMBER',
    password_hash VARCHAR(100) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_department ON users(department_id);
CREATE INDEX idx_users_active     ON users(active);

INSERT INTO departments (code, name) VALUES
    ('FINANCE',    'Finance'),
    ('IT',         'IT'),
    ('HR',         'HR'),
    ('OPERATIONS', 'Operations');

-- Training seed credentials: admin/admin123 (ADMIN), alice/alice123 (MEMBER, Finance)
INSERT INTO users (username, display_name, email, department_id, role, password_hash) VALUES
    ('admin', 'FlowOps Admin', NULL, NULL, 'ADMIN',
     '$2a$10$CwmvLMJ0OhqWyEoid6A/8.pG1QtSx6j8RgF9XcTS0EwI2HO6CTTmG'),
    ('alice', 'Alice Adams', 'alice@example.com',
     (SELECT id FROM departments WHERE code = 'FINANCE'), 'MEMBER',
     '$2a$10$4Ihmk4ugvqYKJnAhX9Qd.OhpfUrdgNi/nEDIgXA23D4Kt.QKqFQ/u');
