-- ============================================================
-- RinBI 元数据库（rinbidb）初始化脚本
-- 由 spring.sql.init 在应用启动时执行（见 application-dev.yml），
-- 所有语句保持幂等，重复启动不会破坏已有数据。
-- ============================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(255) NOT NULL COMMENT 'PBKDF2 密码摘要',
    display_name  VARCHAR(128) NOT NULL COMMENT '显示名称',
    role          VARCHAR(32)  NOT NULL DEFAULT 'USER' COMMENT '角色：ADMIN / USER',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '0 禁用/拒绝，1 启用，2 待审批',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户';

-- ============================================================
-- 数据源拆分为：
--   data_source       全局连接配置（管理员统一维护）
--   user_data_source  用户与数据源的授权关系（中间表）
-- 旧版 user_data_source 同时保存连接配置和 user_id，这里做一次幂等迁移。
-- ============================================================
SET @has_legacy_user_data_source = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_data_source'
      AND COLUMN_NAME = 'db_key'
);

SET @has_legacy_backup = (
    SELECT COUNT(*)
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_data_source_legacy'
);
SET @has_legacy_data = @has_legacy_user_data_source + @has_legacy_backup;

SET @drop_legacy_sql = IF(@has_legacy_user_data_source > 0,
    'DROP TABLE IF EXISTS user_data_source_legacy',
    'SELECT 1');
PREPARE legacy_stmt FROM @drop_legacy_sql;
EXECUTE legacy_stmt;
DEALLOCATE PREPARE legacy_stmt;

SET @rename_legacy_sql = IF(@has_legacy_user_data_source > 0,
    'RENAME TABLE user_data_source TO user_data_source_legacy',
    'SELECT 1');
PREPARE legacy_stmt FROM @rename_legacy_sql;
EXECUTE legacy_stmt;
DEALLOCATE PREPARE legacy_stmt;

CREATE TABLE IF NOT EXISTS data_source (
    id                        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    db_key                    VARCHAR(64)  NOT NULL COMMENT '数据源 key，全局唯一',
    name                      VARCHAR(128) NOT NULL COMMENT '显示名称',
    description               VARCHAR(512)          DEFAULT NULL COMMENT '说明',
    db_type                   VARCHAR(32)  NOT NULL COMMENT '数据库类型：mysql / postgresql / h2 等',
    host                      VARCHAR(255)          DEFAULT NULL COMMENT '登录地址',
    port                      INT                   DEFAULT NULL COMMENT '端口号',
    database_name             VARCHAR(128)          DEFAULT NULL COMMENT '库名',
    username                  VARCHAR(128) NOT NULL COMMENT '数据库登录账号',
    password                  VARCHAR(512) NOT NULL COMMENT '数据库登录密码（AES-GCM 加密存储）',
    driver_class_name         VARCHAR(128)          DEFAULT NULL COMMENT 'JDBC 驱动，留空按 db_type 推断',
    jdbc_url                  VARCHAR(1024)         DEFAULT NULL COMMENT '完整 JDBC URL，非空时优先使用',
    jdbc_params               VARCHAR(512)          DEFAULT NULL COMMENT '附加连接参数',
    dialect                   VARCHAR(32)  NOT NULL COMMENT '方言提示，供模型生成 SQL 时参考',
    read_only                 TINYINT      NOT NULL DEFAULT 1 COMMENT '连接是否设为只读',
    max_pool_size             INT          NOT NULL DEFAULT 3 COMMENT '连接池最大连接数',
    connection_timeout_millis INT          NOT NULL DEFAULT 10000 COMMENT '连接超时（毫秒）',
    init_script               VARCHAR(255)          DEFAULT NULL COMMENT '可选 classpath 初始化脚本',
    enabled                   TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用，0 停用',
    created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_data_source_key (db_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '全局查询数据源配置';

CREATE TABLE IF NOT EXISTS user_data_source (
    id             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id        BIGINT   NOT NULL COMMENT '用户 ID',
    data_source_id BIGINT   NOT NULL COMMENT '数据源 ID',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_data_source (user_id, data_source_id),
    KEY idx_user_data_source_source (data_source_id),
    CONSTRAINT fk_user_ds_access_user FOREIGN KEY (user_id)
        REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_ds_access_source FOREIGN KEY (data_source_id)
        REFERENCES data_source (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户可访问的数据源（授权关系）';

-- 迁移旧配置：同名 db_key 只保留最早一条连接配置，其余用户复用该配置。
SET @copy_sources_sql = IF(@has_legacy_data > 0,
    'INSERT IGNORE INTO data_source (id, db_key, name, description, db_type, host, port, database_name, username, password, driver_class_name, jdbc_url, jdbc_params, dialect, read_only, max_pool_size, connection_timeout_millis, init_script, enabled, created_at, updated_at) SELECT l.id, l.db_key, l.name, l.description, l.db_type, l.host, l.port, l.database_name, l.username, l.password, l.driver_class_name, l.jdbc_url, l.jdbc_params, l.dialect, l.read_only, l.max_pool_size, l.connection_timeout_millis, l.init_script, l.enabled, l.created_at, l.updated_at FROM user_data_source_legacy l WHERE l.id = (SELECT MIN(x.id) FROM user_data_source_legacy x WHERE x.db_key = l.db_key)',
    'SELECT 1');
PREPARE legacy_stmt FROM @copy_sources_sql;
EXECUTE legacy_stmt;
DEALLOCATE PREPARE legacy_stmt;

SET @copy_grants_sql = IF(@has_legacy_data > 0,
    'INSERT IGNORE INTO user_data_source (user_id, data_source_id, created_at) SELECT l.user_id, d.id, l.created_at FROM user_data_source_legacy l JOIN data_source d ON d.db_key = l.db_key',
    'SELECT 1');
PREPARE legacy_stmt FROM @copy_grants_sql;
EXECUTE legacy_stmt;
DEALLOCATE PREPARE legacy_stmt;

SET @drop_legacy_sql = IF(@has_legacy_data > 0,
    'DROP TABLE user_data_source_legacy',
    'SELECT 1');
PREPARE legacy_stmt FROM @drop_legacy_sql;
EXECUTE legacy_stmt;
DEALLOCATE PREPARE legacy_stmt;

CREATE TABLE IF NOT EXISTS chat_conversation (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '所属用户',
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话业务 ID',
    title           VARCHAR(200) NOT NULL COMMENT '会话标题（首问摘要）',
    datasource_key  VARCHAR(64)           DEFAULT NULL COMMENT '最近使用的数据源 key',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_conversation_user (user_id, conversation_id),
    KEY idx_chat_conversation_updated (user_id, updated_at),
    CONSTRAINT fk_chat_conversation_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '历史对话';

CREATE TABLE IF NOT EXISTS chat_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_pk BIGINT      NOT NULL COMMENT 'chat_conversation.id',
    role            VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT',
    content         LONGTEXT             DEFAULT NULL COMMENT '问题或答案文本',
    response_json   LONGTEXT             DEFAULT NULL COMMENT '助手消息的完整 ChatResponse（JSON）',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_chat_message_conversation (conversation_pk, id),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_pk)
        REFERENCES chat_conversation (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '历史对话消息';
