CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_account_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    biz_key VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_conversation_biz_key UNIQUE (biz_key)
);

CREATE INDEX IF NOT EXISTS idx_conversation_type_created_at
    ON conversation (type, created_at);

CREATE TABLE IF NOT EXISTS conversation_member (
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    join_sequence BIGINT NOT NULL DEFAULT 0,
    leave_sequence BIGINT,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at DATETIME,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_conversation_member_user_id
    ON conversation_member (user_id);

CREATE INDEX IF NOT EXISTS idx_conversation_member_status
    ON conversation_member (conversation_id, status);

CREATE TABLE IF NOT EXISTS chat_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(128) NOT NULL,
    owner_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    member_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_group_owner_status
    ON chat_group (owner_id, status);

CREATE TABLE IF NOT EXISTS group_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    join_sequence BIGINT NOT NULL DEFAULT 0,
    leave_sequence BIGINT,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at DATETIME,
    CONSTRAINT uk_group_member_group_user UNIQUE (group_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_group_member_group_status
    ON group_member (group_id, status);

CREATE INDEX IF NOT EXISTS idx_group_member_user_status
    ON group_member (user_id, status);

CREATE TABLE IF NOT EXISTS message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL,
    client_message_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    receiver_id BIGINT,
    content CLOB NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_message_message_id UNIQUE (message_id),
    CONSTRAINT uk_message_sender_client_id UNIQUE (sender_id, client_message_id),
    CONSTRAINT uk_message_conversation_sequence UNIQUE (conversation_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_message_receiver_created_at
    ON message (receiver_id, created_at);

CREATE INDEX IF NOT EXISTS idx_message_conversation_created_at
    ON message (conversation_id, created_at);
