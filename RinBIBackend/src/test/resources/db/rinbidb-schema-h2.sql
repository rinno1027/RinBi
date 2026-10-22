-- 测试专用元数据库脚本（H2 MySQL 兼容模式），与 db/rinbidb-schema.sql 结构保持一致。
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER',
    status        TINYINT      NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE IF NOT EXISTS data_source (
    id                        BIGINT       NOT NULL AUTO_INCREMENT,
    db_key                    VARCHAR(64)  NOT NULL,
    name                      VARCHAR(128) NOT NULL,
    description               VARCHAR(512)          DEFAULT NULL,
    db_type                   VARCHAR(32)  NOT NULL,
    host                      VARCHAR(255)          DEFAULT NULL,
    port                      INT                   DEFAULT NULL,
    database_name             VARCHAR(128)          DEFAULT NULL,
    username                  VARCHAR(128) NOT NULL,
    password                  VARCHAR(512) NOT NULL,
    driver_class_name         VARCHAR(128)          DEFAULT NULL,
    jdbc_url                  VARCHAR(1024)         DEFAULT NULL,
    jdbc_params               VARCHAR(512)          DEFAULT NULL,
    dialect                   VARCHAR(32)  NOT NULL,
    read_only                 TINYINT      NOT NULL DEFAULT 1,
    max_pool_size             INT          NOT NULL DEFAULT 3,
    connection_timeout_millis INT          NOT NULL DEFAULT 10000,
    init_script               VARCHAR(255)          DEFAULT NULL,
    enabled                   TINYINT      NOT NULL DEFAULT 1,
    created_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_data_source_key UNIQUE (db_key)
);

CREATE TABLE IF NOT EXISTS user_data_source (
    id             BIGINT    NOT NULL AUTO_INCREMENT,
    user_id        BIGINT    NOT NULL,
    data_source_id BIGINT    NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_data_source UNIQUE (user_id, data_source_id),
    CONSTRAINT fk_user_ds_access_user FOREIGN KEY (user_id)
        REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_ds_access_source FOREIGN KEY (data_source_id)
        REFERENCES data_source (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_data_source_source
    ON user_data_source (data_source_id);


CREATE TABLE IF NOT EXISTS chat_conversation (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    datasource_key  VARCHAR(64)           DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_chat_conversation_user UNIQUE (user_id, conversation_id),
    CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id)
        REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_pk BIGINT      NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         CLOB                 DEFAULT NULL,
    response_json   CLOB                 DEFAULT NULL,
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_pk)
        REFERENCES chat_conversation (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_conversation_updated ON chat_conversation (user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_chat_message_conversation ON chat_message (conversation_pk, id);
