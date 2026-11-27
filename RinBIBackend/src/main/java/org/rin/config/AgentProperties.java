package org.rin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 全部可配置项，对应 application.yml 中的 agent.*。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Llm llm = new Llm();
    private Sql sql = new Sql();
    private Memory memory = new Memory();
    private Web web = new Web();
    private Stream stream = new Stream();
    private Auth auth = new Auth();
    private Security security = new Security();
    private Bootstrap bootstrap = new Bootstrap();
    private Report report = new Report();

    @Data
    public static class Llm {
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        /**
         * 结果整理阶段使用的推理模型；留空时回退到 modelName。
         */
        private String reasoningModelName;
        /**
         * 推理强度（如 low / high / max），仅对支持该参数的模型生效；留空则不发送。
         */
        private String reasoningEffort;
        /**
         * 思考模式开关，写入请求体的 thinking.type（DeepSeek 支持 enabled / disabled）。
         * 留空则不发送该字段，由服务端默认值决定；不支持该参数的服务请保持为空。
         */
        private String thinkingType;
        private Double temperature;
        private Integer maxTokens;
        private Integer timeoutSeconds;
        private Integer maxRetries;
        private Boolean logRequests;
        private Boolean logResponses;
        private Boolean returnThinking;
        private Ollama ollama = new Ollama();
    }

    @Data
    public static class Ollama {
        private Boolean enabled;
        private String baseUrl;
        private String modelName;
    }

    @Data
    public static class Sql {
        /**
         * 单次查询结果最大字符数，超过后不返回数据，并提示用户分步查询。
         */
        private Integer maxResultChars;
        /**
         * 单次查询最多读取的行数。
         */
        private Integer maxRows;
        private Integer queryTimeoutSeconds;
        private Integer maxSchemaChars;
        private Integer maxSqlLength;
        private Integer schemaCacheSeconds;
        private Integer retryMaxAttempts;
        private Long retryBackoffMillis;
        private Integer formatRowLimit;
        private Integer formatMaxChars;
        /**
         * 是否在响应中返回实际执行的 SQL。SQL 会暴露真实表名/字段名，
         * 默认关闭以对最终用户隐藏库表结构；确有调试需要时再开启。
         */
        private Boolean exposeSql = false;
    }

    @Data
    public static class Memory {
        private Integer maxExchanges;
        private Integer ttlMinutes;
        private Integer maxStoredAnswerChars;
    }

    @Data
    public static class Web {
        private List<String> allowedOrigins = new ArrayList<>();
    }

    /**
     * 流式对话专用线程池（SSE 请求处理）参数，对应 agent.stream.*。
     */
    @Data
    public static class Stream {
        /**
         * 核心线程数。
         */
        private Integer corePoolSize = 8;
        /**
         * 最大线程数；小于核心线程数时按核心线程数处理。
         */
        private Integer maxPoolSize = 32;
        /**
         * 任务队列容量；小于等于 0 时使用 SynchronousQueue（不排队，直接扩容线程）。
         */
        private Integer queueCapacity = 100;
        /**
         * 非核心线程空闲存活时间（秒）。
         */
        private Long keepAliveSeconds = 60L;
        /**
         * 线程名前缀，实际线程名为“前缀 + 自增序号”。
         */
        private String threadNamePrefix = "agent-stream-";
        /**
         * 是否使用守护线程。
         */
        private Boolean daemon = true;
    }

    /**
     * 登录会话配置，对应 agent.auth.*。
     */
    @Data
    public static class Auth {
        /**
         * 登录 token 有效期（分钟）。
         */
        private Integer tokenTtlMinutes = 720;
    }

    /**
     * 安全相关配置，对应 agent.security.*。
     */
    @Data
    public static class Security {
        /**
         * 数据源密码加密密钥；更换后历史数据源密码需要重新录入。
         */
        private String secret;
    }

    /**
     * 首次启动初始化默认账号与演示数据源，对应 agent.bootstrap.*。
     */
    @Data
    public static class Bootstrap {
        private Boolean enabled = true;
        private String adminUsername;
        private String adminPassword;
        private String adminDisplayName;
        private Boolean demoDatasourceEnabled = true;
    }

    /**
     * 智能报表配置，对应 agent.report.*。
     */
    @Data
    public static class Report {
        /**
         * 生成文件的保存目录；相对路径基于应用启动目录。
         */
        private String storageDir = "data/reports";
        /**
         * 模板上传大小上限（字节），默认 10MB；需同时调整 multipart 限制。
         */
        private Long maxUploadBytes = 10L * 1024 * 1024;
        /**
         * 模板解析时最多读取的行数，避免超大模板拖慢提示词构造。
         */
        private Integer maxTemplateRows = 200;
        /**
         * 模板解析时最多读取的列数。
         */
        private Integer maxTemplateColumns = 60;
        /**
         * 单个表格区域最多回填的数据行数。
         */
        private Integer maxFillRows = 5000;
        /**
         * 单次报表最多允许执行的 SQL 条数。
         */
        private Integer maxSqlCount = 5;
        /**
         * 生成文件保留天数；小于等于 0 表示不自动清理过期记录。
         */
        private Integer retentionDays = 7;
        /**
         * 单轮报表提示词（模板结构 + 表结构 + 历史 + 本轮需求）的最大字符数。
         */
        private Integer maxPromptChars = 30_000;
        /**
         * 同一报表会话累计可用的输入字符预算；超出后需要新建会话并重新上传模板。
         */
        private Integer maxConversationChars = 120_000;
        /**
         * 生成报表时最多带上的历史问答轮数，避免多轮调整时上下文无限增长。
         */
        private Integer historyExchanges = 4;
    }
}
