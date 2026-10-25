-- Dev-profile only: two local accounts, password DevPassword123! (never reuse).

INSERT INTO users (username, email, password_hash, full_name, enabled) VALUES
    ('admin.dev', 'admin.dev@example.test', '$2b$10$bZIjoBOTXCU7h6Wx2lrJgeISAOyZtaLE5ExXKlnVz5JzwD6uMp/he', 'Dev Administrator', true),
    ('devops.dev', 'devops.dev@example.test', '$2b$10$bZIjoBOTXCU7h6Wx2lrJgeISAOyZtaLE5ExXKlnVz5JzwD6uMp/he', 'Dev DevOps Engineer', true)
ON CONFLICT (username) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r WHERE u.username = 'admin.dev' AND r.name = 'ADMINISTRATOR'
ON CONFLICT DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r WHERE u.username = 'devops.dev' AND r.name = 'BACKEND_DEVOPS_ENGINEER'
ON CONFLICT DO NOTHING;
