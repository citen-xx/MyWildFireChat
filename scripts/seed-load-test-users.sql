INSERT INTO user_account (id, username, password_hash, status)
WITH RECURSIVE numbers AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1
    FROM numbers
    WHERE n < 100
)
SELECT
    2000 + n,
    CONCAT('load', LPAD(n, 3, '0')),
    '{noop}password123',
    'ACTIVE'
FROM numbers
ON DUPLICATE KEY UPDATE
    username = VALUES(username),
    password_hash = VALUES(password_hash),
    status = VALUES(status);
