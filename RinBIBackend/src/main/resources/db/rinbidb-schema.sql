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

-- ============================================================
-- 统计规则：用户为某个数据源维护的业务统计口径。
-- 规则本身可被拥有该数据源权限的用户共享使用，但新增、修改、删除仅限管理员。
-- 规则选择结果仅注入查询规划提示词，不参与结果后处理。
-- ============================================================
CREATE TABLE IF NOT EXISTS stat_rule (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    data_source_key  VARCHAR(64)   NOT NULL COMMENT '所属数据源 key',
    version_no       VARCHAR(32)   NOT NULL COMMENT '版本号（每次保存更新）',
    rule_code        VARCHAR(50)   NOT NULL COMMENT '规则代码，全局唯一',
    rule_name        VARCHAR(128)  NOT NULL COMMENT '规则名称',
    rule_description VARCHAR(512)           DEFAULT NULL COMMENT '规则描述',
    formula          VARCHAR(1024)          DEFAULT NULL COMMENT '计算公式',
    business_rule    TEXT                   DEFAULT NULL COMMENT '业务规则说明',
    remark           VARCHAR(512)           DEFAULT NULL COMMENT '备注',
    created_by       BIGINT        NOT NULL COMMENT '创建人 sys_user.id',
    updated_by       BIGINT        NOT NULL COMMENT '最后修改人 sys_user.id',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stat_rule_code (rule_code),
    KEY idx_stat_rule_source (data_source_key),
    CONSTRAINT fk_stat_rule_source FOREIGN KEY (data_source_key)
        REFERENCES data_source (db_key) ON DELETE CASCADE,
    CONSTRAINT fk_stat_rule_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id),
    CONSTRAINT fk_stat_rule_updated_by FOREIGN KEY (updated_by)
        REFERENCES sys_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '统计规则';

CREATE TABLE IF NOT EXISTS stat_rule_input_field (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    rule_id           BIGINT       NOT NULL COMMENT 'stat_rule.id',
    source_table      VARCHAR(128) NOT NULL COMMENT '来源表名',
    field_name        VARCHAR(128) NOT NULL COMMENT '字段名',
    field_description VARCHAR(512)          DEFAULT NULL COMMENT '字段说明',
    sort_order        INT          NOT NULL DEFAULT 0 COMMENT '展示/使用顺序',
    created_by        BIGINT       NOT NULL COMMENT '创建人 sys_user.id',
    updated_by        BIGINT       NOT NULL COMMENT '最后修改人 sys_user.id',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stat_rule_field (rule_id, source_table, field_name),
    CONSTRAINT fk_stat_rule_field_rule FOREIGN KEY (rule_id)
        REFERENCES stat_rule (id) ON DELETE CASCADE,
    CONSTRAINT fk_stat_rule_field_created_by FOREIGN KEY (created_by)
        REFERENCES sys_user (id),
    CONSTRAINT fk_stat_rule_field_updated_by FOREIGN KEY (updated_by)
        REFERENCES sys_user (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '统计规则输入字段';

CREATE TABLE IF NOT EXISTS chat_conversation (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '所属用户',
    conversation_id VARCHAR(64)  NOT NULL COMMENT '会话业务 ID',
    title           VARCHAR(200) NOT NULL COMMENT '会话标题（首问摘要）',
    feature_type    VARCHAR(16)  NOT NULL DEFAULT 'DATA_QA' COMMENT '功能类型：DATA_QA 数据问答 / REPORT 智能报表',
    datasource_key  VARCHAR(64)           DEFAULT NULL COMMENT '最近使用的数据源 key',
    input_chars     BIGINT       NOT NULL DEFAULT 0 COMMENT '智能报表会话累计占用的模型输入字符数',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_conversation_user (user_id, conversation_id),
    KEY idx_chat_conversation_updated (user_id, updated_at),
    KEY idx_chat_conversation_feature (user_id, feature_type, updated_at),
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

-- ============================================================
-- 兼容旧库：chat_conversation 新增 feature_type / input_chars，
-- 区分「数据问答」与「智能报表」两种功能，并记录报表会话的输入预算消耗。
-- 所有语句幂等，已存在时直接跳过。
-- ============================================================
SET @has_conversation_feature_type = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_conversation'
      AND COLUMN_NAME = 'feature_type'
);
SET @add_conversation_feature_type_sql = IF(@has_conversation_feature_type = 0,
    'ALTER TABLE chat_conversation ADD COLUMN feature_type VARCHAR(16) NOT NULL DEFAULT ''DATA_QA'' COMMENT ''功能类型：DATA_QA 数据问答 / REPORT 智能报表'' AFTER title',
    'SELECT 1');
PREPARE conversation_ddl_stmt FROM @add_conversation_feature_type_sql;
EXECUTE conversation_ddl_stmt;
DEALLOCATE PREPARE conversation_ddl_stmt;

SET @has_conversation_input_chars = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_conversation'
      AND COLUMN_NAME = 'input_chars'
);
SET @add_conversation_input_chars_sql = IF(@has_conversation_input_chars = 0,
    'ALTER TABLE chat_conversation ADD COLUMN input_chars BIGINT NOT NULL DEFAULT 0 COMMENT ''智能报表会话累计占用的模型输入字符数'' AFTER datasource_key',
    'SELECT 1');
PREPARE conversation_ddl_stmt FROM @add_conversation_input_chars_sql;
EXECUTE conversation_ddl_stmt;
DEALLOCATE PREPARE conversation_ddl_stmt;

SET @has_conversation_feature_index = (
    SELECT COUNT(*)
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_conversation'
      AND INDEX_NAME = 'idx_chat_conversation_feature'
);
SET @add_conversation_feature_index_sql = IF(@has_conversation_feature_index = 0,
    'ALTER TABLE chat_conversation ADD KEY idx_chat_conversation_feature (user_id, feature_type, updated_at)',
    'SELECT 1');
PREPARE conversation_ddl_stmt FROM @add_conversation_feature_index_sql;
EXECUTE conversation_ddl_stmt;
DEALLOCATE PREPARE conversation_ddl_stmt;

-- ============================================================
-- 智能报表生成文件记录：文件实体保存在 agent.report.storage-dir，
-- 数据库只保存元信息与归属，下载时按 user_id 严格校验。
-- ============================================================
CREATE TABLE IF NOT EXISTS report_file (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '所属用户 sys_user.id',
    conversation_id VARCHAR(64)           DEFAULT NULL COMMENT '关联会话业务 ID',
    template_id     BIGINT                DEFAULT NULL COMMENT '生成该文件所用的模板 report_template.id',
    file_name       VARCHAR(255) NOT NULL COMMENT '下载时展示的文件名',
    stored_name     VARCHAR(255) NOT NULL COMMENT '存储目录内的文件名',
    content_type    VARCHAR(128) NOT NULL DEFAULT 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' COMMENT 'MIME 类型',
    file_size       BIGINT       NOT NULL DEFAULT 0 COMMENT '文件字节数',
    status          VARCHAR(16)  NOT NULL DEFAULT 'READY' COMMENT 'READY / FAILED',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_report_file_user (user_id, created_at),
    CONSTRAINT fk_report_file_user FOREIGN KEY (user_id)
        REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '智能报表生成文件';

SET @has_report_file_template_id = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'report_file'
      AND COLUMN_NAME = 'template_id'
);
SET @add_report_file_template_id_sql = IF(@has_report_file_template_id = 0,
    'ALTER TABLE report_file ADD COLUMN template_id BIGINT DEFAULT NULL COMMENT ''生成该文件所用的模板 report_template.id'' AFTER conversation_id',
    'SELECT 1');
PREPARE report_file_ddl_stmt FROM @add_report_file_template_id_sql;
EXECUTE report_file_ddl_stmt;
DEALLOCATE PREPARE report_file_ddl_stmt;

-- 兼容旧数据：早期版本生成的报表会话没有 feature_type（默认 DATA_QA），
-- 这里按 report_file 的关联关系把它们归入「智能报表」历史，避免混进数据问答列表。
UPDATE chat_conversation c
SET c.feature_type = 'REPORT'
WHERE c.feature_type = 'DATA_QA'
  AND EXISTS (
      SELECT 1 FROM report_file f
      WHERE f.conversation_id = c.conversation_id
        AND f.user_id = c.user_id
  );

-- ============================================================
-- 智能报表导入模板：用户上传的 Excel 模板同样落盘保存，
-- 一个会话（conversation_id）只允许导入一个模板；
-- plan_json 保存最近一次可用的取数计划，供后续多轮调整复用。
-- ============================================================
CREATE TABLE IF NOT EXISTS report_template (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT        NOT NULL COMMENT '所属用户 sys_user.id',
    conversation_id VARCHAR(64)   NOT NULL COMMENT '关联会话业务 ID（一个会话仅允许一个模板）',
    template_name   VARCHAR(255)  NOT NULL COMMENT '用户上传时的模板文件名',
    stored_name     VARCHAR(255)  NOT NULL COMMENT '模板存储目录内的文件名',
    content_type    VARCHAR(128)  NOT NULL DEFAULT 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' COMMENT 'MIME 类型',
    file_size       BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    sheet_summary   VARCHAR(1024)          DEFAULT NULL COMMENT '模板结构摘要（工作表与已用区域）',
    plan_json       LONGTEXT               DEFAULT NULL COMMENT '最近一次可用的报表取数计划 JSON',
    status          VARCHAR(16)   NOT NULL DEFAULT 'READY' COMMENT 'READY / FAILED',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_template_conversation (user_id, conversation_id),
    KEY idx_report_template_user (user_id, created_at),
    CONSTRAINT fk_report_template_user FOREIGN KEY (user_id)
        REFERENCES sys_user (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '智能报表导入模板';

