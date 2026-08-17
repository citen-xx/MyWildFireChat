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
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_conversation_member_user_id
    ON conversation_member (user_id);

CREATE TABLE IF NOT EXISTS message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL,
    client_message_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
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
