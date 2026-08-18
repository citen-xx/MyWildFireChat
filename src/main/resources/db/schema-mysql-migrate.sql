-- Local schema upgrade for databases created before Phase 6 group chat.
-- MySQL does not support ADD COLUMN IF NOT EXISTS. Each ALTER is intentionally
-- separate and Spring SQL initialization continues when a column already exists.

ALTER TABLE conversation_member
    ADD COLUMN role VARCHAR(32) NULL AFTER user_id;

ALTER TABLE conversation_member
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' AFTER role;

ALTER TABLE conversation_member
    ADD COLUMN join_sequence BIGINT NOT NULL DEFAULT 0 AFTER status;

ALTER TABLE conversation_member
    ADD COLUMN leave_sequence BIGINT NULL AFTER join_sequence;

ALTER TABLE conversation_member
    ADD COLUMN left_at DATETIME NULL AFTER joined_at;

UPDATE conversation_member
SET role = 'MEMBER'
WHERE role IS NULL;

ALTER TABLE message
    MODIFY COLUMN receiver_id BIGINT NULL;
