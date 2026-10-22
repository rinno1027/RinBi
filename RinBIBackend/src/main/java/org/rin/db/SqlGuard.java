package org.rin.db;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.rin.config.AgentProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 只读 SQL 安全校验。
 *
 * <p>双重防线：</p>
 * <ul>
 *     <li>JSqlParser 语法解析：只允许单条 SELECT / WITH ... SELECT；</li>
 *     <li>去除字符串/注释后做关键字黑名单检查：拦截 DML/DDL、SELECT INTO、锁、危险函数等。</li>
 * </ul>
 */
@Component
public class SqlGuard {

    private static final int MAX_STATEMENTS=1;

    @Autowired
    private AgentProperties properties;


    /**
     * 注意：REPLACE 也是 MySQL 的字符串函数，因此不在此列表中；
     * REPLACE INTO 不是 Select，会被 JSqlParser 拦截。
     */
    private static final Pattern FORBIDDEN_KEYWORDS = Pattern.compile(
            "\\b(INSERT|UPDATE|DELETE|MERGE|UPSERT|DROP|ALTER|CREATE|TRUNCATE|GRANT|REVOKE|"
                    + "CALL|EXEC|EXECUTE|RENAME|COMMENT|ANALYZE|VACUUM|LOCK|UNLOCK|SET|USE|"
                    + "LOAD|INTO|COPY|ATTACH|DETACH|PRAGMA|SHUTDOWN)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DANGEROUS_FUNCTIONS = Pattern.compile(
            "\\b(LOAD_FILE|SLEEP|BENCHMARK|PG_SLEEP|XP_CMDSHELL|OPENROWSET|OPENQUERY|"
                    + "DBMS_[A-Z0-9_]+|UTL_[A-Z0-9_]+)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern INTO_FILE = Pattern.compile(
            "\\bINTO\\s+(OUTFILE|DUMPFILE)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern LOCKING_CLAUSE = Pattern.compile(
            "\\bFOR\\s+(UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE)\\b"
                    + "|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 校验并返回去掉末尾分号的 SQL。
     *
     * @throws UnsafeSqlException SQL 不是单条只读查询
     */
    public String validate(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new UnsafeSqlException("SQL 为空");
        }
        String candidate = sql.trim();
        while (candidate.endsWith(";")) {
            candidate = candidate.substring(0, candidate.length() - 1).trim();
        }
        if (candidate.isEmpty()) {
            throw new UnsafeSqlException("SQL 为空");
        }
        if (candidate.length() > properties.getSql().getMaxSqlLength()) {
            throw new UnsafeSqlException("SQL 长度超过限制（最大 " + properties.getSql().getMaxSqlLength() + " 字符）");
        }

        Statements statements = parse(candidate);
        List<Statement> statementList = statements.getStatements();
        if (statementList == null || statementList.size() != MAX_STATEMENTS) {
            throw new UnsafeSqlException("只允许执行一条 SQL 语句");
        }
        Statement statement = statementList.get(0);
        if (!(statement instanceof Select)) {
            throw new UnsafeSqlException("只允许执行 SELECT / WITH 查询，禁止 DML、DDL 及管理语句");
        }

        Select select = (Select) statement;
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                validateSelectBody(withItem);
            }
        }
        validateSelectBody(select.getSelectBody());

        String normalized = stripLiteralsAndComments(candidate).toUpperCase(Locale.ROOT);
        java.util.regex.Matcher keywordMatcher = FORBIDDEN_KEYWORDS.matcher(normalized);
        if (keywordMatcher.find()) {
            throw new UnsafeSqlException("SQL 包含禁止的关键字: " + keywordMatcher.group(1)
                    + "，本系统只允许只读查询");
        }
        if (DANGEROUS_FUNCTIONS.matcher(normalized).find()) {
            throw new UnsafeSqlException("SQL 包含禁止的危险函数，已拒绝执行");
        }
        if (INTO_FILE.matcher(normalized).find()) {
            throw new UnsafeSqlException("禁止使用 SELECT ... INTO OUTFILE/DUMPFILE 导出文件");
        }
        if (LOCKING_CLAUSE.matcher(normalized).find()) {
            throw new UnsafeSqlException("禁止使用 FOR UPDATE / FOR SHARE 等加锁查询");
        }
        return candidate;
    }

    private Statements parse(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null) {
                throw new UnsafeSqlException("SQL 无法解析");
            }
            return statements;
        } catch (JSQLParserException e) {
            throw new UnsafeSqlException("SQL 语法无法解析，已拒绝执行: " + rootMessage(e), e);
        }
    }

    private void validateSelectBody(SelectBody body) {
        if (body == null) {
            throw new UnsafeSqlException("SQL 缺少 SELECT 主体");
        }
        if (body instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) body;
            if (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()) {
                throw new UnsafeSqlException("禁止使用 SELECT ... INTO 创建或写入新表");
            }
            if (plainSelect.isForUpdate()) {
                throw new UnsafeSqlException("禁止使用 FOR UPDATE 加锁查询");
            }
            return;
        }
        if (body instanceof SetOperationList) {
            List<SelectBody> selects = ((SetOperationList) body).getSelects();
            if (selects != null) {
                for (SelectBody select : selects) {
                    validateSelectBody(select);
                }
            }
            return;
        }
        if (body instanceof WithItem) {
            WithItem withItem = (WithItem) body;
            if (withItem.getSubSelect() != null) {
                validateSelectBody(withItem.getSubSelect().getSelectBody());
            } else if (withItem.getItemsList() == null) {
                throw new UnsafeSqlException("不支持的 WITH 子句结构，已拒绝执行");
            }
            return;
        }
        throw new UnsafeSqlException("不支持的 SELECT 语句结构，已拒绝执行");
    }

    /**
     * 将字符串字面量、引号标识符、注释替换为占位符，避免关键字黑名单误伤或绕过。
     */
    private String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                out.append("''");
                i = skipQuoted(sql, i, '\'');
                continue;
            }
            if (c == '"') {
                out.append('x');
                i = skipQuoted(sql, i, '"');
                continue;
            }
            if (c == '`') {
                out.append('x');
                i = skipQuoted(sql, i, '`');
                continue;
            }
            if (c == '[') {
                out.append('x');
                i++;
                while (i < sql.length() && sql.charAt(i) != ']') {
                    i++;
                }
                if (i < sql.length()) {
                    i++;
                }
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                out.append(' ');
                i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '#') {
                out.append(' ');
                i++;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                out.append(' ');
                i += 2;
                while (i + 1 < sql.length()
                        && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, sql.length());
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < sql.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null) {
            return current.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}
