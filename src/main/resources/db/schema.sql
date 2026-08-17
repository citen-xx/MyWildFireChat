CREATE TABLE IF NOT EXISTS user_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    type VARCHAR(32) NOT NULL,
    biz_key VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversation_biz_key (biz_key),
    KEY idx_conversation_type_created_at (type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS conversation_member (
    conversation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (conversation_id, user_id),
    KEY idx_conversation_member_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id VARCHAR(64) NOT NULL,
    client_message_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    receiver_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_message_id (message_id),
    UNIQUE KEY uk_message_sender_client_id (sender_id, client_message_id),
    UNIQUE KEY uk_message_conversation_sequence (conversation_id, sequence),
    KEY idx_message_receiver_created_at (receiver_id, created_at),
    KEY idx_message_conversation_created_at (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
