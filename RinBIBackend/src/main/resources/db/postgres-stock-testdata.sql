-- ============================================================
-- 股票信息测试库初始化脚本（PostgreSQL）
-- 目标库：本地测试库 test
-- 执行方式：
--   psql -U postgres -d test -f src/main/resources/db/postgres-stock-testdata.sql
-- 注意：脚本会 DROP 并重建以下三张表，请勿在存放正式数据的库中执行。
-- 数据量：
--   stock                   30 条（股票基本信息）
--   stock_daily_quote      450 条（30 只股票 × 15 个交易日）
--   stock_financial_report 120 条（30 只股票 × 4 个季度）
--   合计 600 条
-- 说明：使用 setseed 固定随机序列，重复执行可获得相同数据。
-- ============================================================

SET client_encoding = 'UTF8';
SELECT setseed(0.20261021);

BEGIN;

DROP TABLE IF EXISTS stock_financial_report;
DROP TABLE IF EXISTS stock_daily_quote;
DROP TABLE IF EXISTS stock;

-- ------------------------------------------------------------
-- 股票基本信息
-- ------------------------------------------------------------
CREATE TABLE stock (
    id          BIGINT       PRIMARY KEY,
    stock_code  VARCHAR(10)  NOT NULL UNIQUE,
    stock_name  VARCHAR(64)  NOT NULL,
    exchange    VARCHAR(8)   NOT NULL,
    industry    VARCHAR(32)  NOT NULL,
    list_date   DATE         NOT NULL,
    total_share BIGINT       NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT '正常'
);

COMMENT ON TABLE stock IS '股票基本信息';
COMMENT ON COLUMN stock.id IS '股票编号';
COMMENT ON COLUMN stock.stock_code IS '股票代码';
COMMENT ON COLUMN stock.stock_name IS '股票名称';
COMMENT ON COLUMN stock.exchange IS '交易所：SSE 上交所 / SZSE 深交所 / BSE 北交所';
COMMENT ON COLUMN stock.industry IS '所属行业';
COMMENT ON COLUMN stock.list_date IS '上市日期';
COMMENT ON COLUMN stock.total_share IS '总股本（万股）';
COMMENT ON COLUMN stock.status IS '交易状态：正常 / ST';

-- ------------------------------------------------------------
-- 股票日行情
-- ------------------------------------------------------------
CREATE TABLE stock_daily_quote (
    id            BIGSERIAL    PRIMARY KEY,
    stock_id      BIGINT       NOT NULL REFERENCES stock (id),
    trade_date    DATE         NOT NULL,
    open_price    NUMERIC(10, 2) NOT NULL,
    high_price    NUMERIC(10, 2) NOT NULL,
    low_price     NUMERIC(10, 2) NOT NULL,
    close_price   NUMERIC(10, 2) NOT NULL,
    prev_close    NUMERIC(10, 2) NOT NULL,
    volume        BIGINT       NOT NULL,
    amount        NUMERIC(18, 2) NOT NULL,
    turnover_rate NUMERIC(6, 2)  NOT NULL,
    pct_change    NUMERIC(6, 2)  NOT NULL,
    CONSTRAINT uk_stock_daily_quote UNIQUE (stock_id, trade_date),
    CONSTRAINT ck_stock_daily_quote_price CHECK (
        low_price <= open_price AND low_price <= close_price
        AND high_price >= open_price AND high_price >= close_price
        AND low_price > 0
    )
);

CREATE INDEX idx_stock_daily_quote_trade_date ON stock_daily_quote (trade_date);

COMMENT ON TABLE stock_daily_quote IS '股票日行情';
COMMENT ON COLUMN stock_daily_quote.id IS '行情记录编号';
COMMENT ON COLUMN stock_daily_quote.stock_id IS '股票编号';
COMMENT ON COLUMN stock_daily_quote.trade_date IS '交易日期';
COMMENT ON COLUMN stock_daily_quote.open_price IS '开盘价（元）';
COMMENT ON COLUMN stock_daily_quote.high_price IS '最高价（元）';
COMMENT ON COLUMN stock_daily_quote.low_price IS '最低价（元）';
COMMENT ON COLUMN stock_daily_quote.close_price IS '收盘价（元）';
COMMENT ON COLUMN stock_daily_quote.prev_close IS '前收盘价（元）';
COMMENT ON COLUMN stock_daily_quote.volume IS '成交量（股）';
COMMENT ON COLUMN stock_daily_quote.amount IS '成交额（元）';
COMMENT ON COLUMN stock_daily_quote.turnover_rate IS '换手率（%）';
COMMENT ON COLUMN stock_daily_quote.pct_change IS '涨跌幅（%）';

-- ------------------------------------------------------------
-- 股票季度财务报告
-- ------------------------------------------------------------
CREATE TABLE stock_financial_report (
    id                  BIGSERIAL      PRIMARY KEY,
    stock_id            BIGINT         NOT NULL REFERENCES stock (id),
    report_date         DATE           NOT NULL,
    revenue             NUMERIC(18, 2) NOT NULL,
    net_profit          NUMERIC(18, 2) NOT NULL,
    total_assets        NUMERIC(18, 2) NOT NULL,
    total_liabilities   NUMERIC(18, 2) NOT NULL,
    operating_cash_flow NUMERIC(18, 2) NOT NULL,
    roe                 NUMERIC(6, 2)  NOT NULL,
    eps                 NUMERIC(10, 4) NOT NULL,
    CONSTRAINT uk_stock_financial_report UNIQUE (stock_id, report_date)
);

CREATE INDEX idx_stock_financial_report_report_date ON stock_financial_report (report_date);

COMMENT ON TABLE stock_financial_report IS '股票季度财务报告';
COMMENT ON COLUMN stock_financial_report.id IS '报告记录编号';
COMMENT ON COLUMN stock_financial_report.stock_id IS '股票编号';
COMMENT ON COLUMN stock_financial_report.report_date IS '报告期';
COMMENT ON COLUMN stock_financial_report.revenue IS '营业收入（元）';
COMMENT ON COLUMN stock_financial_report.net_profit IS '净利润（元）';
COMMENT ON COLUMN stock_financial_report.total_assets IS '总资产（元）';
COMMENT ON COLUMN stock_financial_report.total_liabilities IS '总负债（元）';
COMMENT ON COLUMN stock_financial_report.operating_cash_flow IS '经营活动现金流净额（元）';
COMMENT ON COLUMN stock_financial_report.roe IS '净资产收益率（%）';
COMMENT ON COLUMN stock_financial_report.eps IS '每股收益（元）';

-- ------------------------------------------------------------
-- 股票基本信息数据（30 条）
-- 说明：公司名称与代码均为测试用的虚构数据
-- ------------------------------------------------------------
INSERT INTO stock (id, stock_code, stock_name, exchange, industry, list_date, total_share, status) VALUES
(1,  '600101', '华云科技',   'SSE',  '信息技术', DATE '1999-11-10', 180000, '正常'),
(2,  '600102', '江海能源',   'SSE',  '能源',     DATE '2001-06-18', 420000, '正常'),
(3,  '600103', '北方重工',   'SSE',  '工业',     DATE '2000-03-09', 250000, '正常'),
(4,  '600104', '东方医药',   'SSE',  '医药生物', DATE '1998-09-22',  96000, '正常'),
(5,  '600105', '海通消费',   'SSE',  '可选消费', DATE '2003-12-05', 310000, '正常'),
(6,  '600106', '金穗食品',   'SSE',  '主要消费', DATE '2002-08-16',  88000, '正常'),
(7,  '600107', '城建发展',   'SSE',  '房地产',   DATE '1999-04-28', 520000, 'ST'),
(8,  '600108', '铁路物流',   'SSE',  '交通运输', DATE '2006-07-31', 150000, '正常'),
(9,  '600109', '蓝海银行',   'SSE',  '金融',     DATE '2007-10-12', 680000, '正常'),
(10, '600110', '恒新材',     'SSE',  '原材料',   DATE '2010-05-20', 205000, '正常'),
(11, '000201', '深科电子',   'SZSE', '信息技术', DATE '1997-06-26', 160000, '正常'),
(12, '000202', '岭南家电',   'SZSE', '可选消费', DATE '2004-02-11', 135000, '正常'),
(13, '000203', '广厦地产',   'SZSE', '房地产',   DATE '2008-09-03', 300000, 'ST'),
(14, '002301', '鹏城软件',   'SZSE', '信息技术', DATE '2010-01-15',  74000, '正常'),
(15, '002302', '南岭医药',   'SZSE', '医药生物', DATE '2011-07-08', 190000, '正常'),
(16, '002303', '珠江纺织',   'SZSE', '可选消费', DATE '2005-11-21', 112000, '正常'),
(17, '002304', '粤海能源',   'SZSE', '能源',     DATE '2012-03-30', 260000, '正常'),
(18, '002305', '华中食品',   'SZSE', '主要消费', DATE '2014-06-09',  98000, '正常'),
(19, '002306', '长江传媒',   'SZSE', '传媒',     DATE '2009-12-18',  45000, '正常'),
(20, '300401', '星云智能',   'SZSE', '工业',     DATE '2015-04-24', 122000, '正常'),
(21, '300402', '光谷通信',   'SZSE', '电信业务', DATE '2016-08-05', 158000, '正常'),
(22, '300403', '新元材料',   'SZSE', '原材料',   DATE '2017-10-27',  64000, '正常'),
(23, '300404', '绿源环保',   'SZSE', '公用事业', DATE '2018-05-14', 210000, '正常'),
(24, '300405', '数联云科',   'SZSE', '信息技术', DATE '2019-09-06',  86000, '正常'),
(25, '300406', '天工机械',   'SZSE', '工业',     DATE '2020-12-11', 175000, 'ST'),
(26, '830501', '北证精密',   'BSE',  '工业',     DATE '2021-06-15',  54000, '正常'),
(27, '830502', '中关村数据', 'BSE',  '信息技术', DATE '2020-07-27',  92000, '正常'),
(28, '430503', '京北农业',   'BSE',  '主要消费', DATE '2022-03-08', 118000, '正常'),
(29, '430504', '燕山化工',   'BSE',  '原材料',   DATE '2021-11-19', 142000, '正常'),
(30, '830505', '北辰医疗',   'BSE',  '医药生物', DATE '2023-04-26', 230000, '正常');

-- ------------------------------------------------------------
-- 股票日行情数据（30 只 × 15 个交易日 = 450 条）
-- 区间：2026-08-03 ~ 2026-08-21（仅工作日）
-- 生成方式：以前收盘价为基准叠加随机游走，保证 OHLC 关系与涨跌幅一致
-- ------------------------------------------------------------
INSERT INTO stock_daily_quote
    (stock_id, trade_date, open_price, high_price, low_price, close_price,
     prev_close, volume, amount, turnover_rate, pct_change)
WITH trading_days AS (
    SELECT g.d::date AS trade_date,
           ROW_NUMBER() OVER (ORDER BY g.d) AS day_no
    FROM generate_series(DATE '2026-08-03', DATE '2026-08-21', INTERVAL '1 day') AS g(d)
    WHERE EXTRACT(ISODOW FROM g.d) BETWEEN 1 AND 5
),
raw_data AS (
    SELECT s.id                            AS stock_id,
           s.total_share,
           d.trade_date,
           d.day_no,
           (8 + (s.id * 13 % 80))::numeric AS base_price,
           (0.012 * (random() - 0.5))::numeric AS daily_return,
           random()                        AS r_open,
           random()                        AS r_high,
           random()                        AS r_low,
           random()                        AS r_volume
    FROM stock s
    CROSS JOIN trading_days d
),
price_walk AS (
    SELECT *,
           base_price * (1 + SUM(daily_return) OVER (
               PARTITION BY stock_id ORDER BY day_no
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
           )) AS raw_close
    FROM raw_data
),
with_prev AS (
    SELECT *,
           COALESCE(
               LAG(raw_close) OVER (PARTITION BY stock_id ORDER BY day_no),
               base_price
           ) AS raw_prev_close
    FROM price_walk
),
bars AS (
    SELECT *,
           ROUND((raw_prev_close * (1 + 0.01 * (r_open - 0.5)))::numeric, 2) AS open_price,
           ROUND(raw_close, 2)                                               AS close_price,
           ROUND(raw_prev_close, 2)                                          AS prev_close
    FROM with_prev
),
volumes AS (
    SELECT *,
           (500000 + FLOOR(r_volume * 9500000))::bigint AS volume
    FROM bars
)
SELECT stock_id,
       trade_date,
       open_price,
       ROUND((GREATEST(open_price, close_price) * (1 + 0.012 * r_high))::numeric, 2) AS high_price,
       ROUND((LEAST(open_price, close_price) * (1 - 0.012 * r_low))::numeric, 2)    AS low_price,
       close_price,
       prev_close,
       volume,
       ROUND(volume * (open_price + close_price) / 2, 2)                  AS amount,
       ROUND(volume * 100.0 / NULLIF(total_share * 10000, 0), 2)          AS turnover_rate,
       ROUND((close_price - prev_close) / NULLIF(prev_close, 0) * 100, 2) AS pct_change
FROM volumes;

-- ------------------------------------------------------------
-- 股票季度财务报告数据（30 只 × 4 个季度 = 120 条）
-- 报告期：2025-09-30、2025-12-31、2026-03-31、2026-06-30
-- 生成方式：按股票规模构造营收基数，叠加季节增长与随机波动
-- ------------------------------------------------------------
INSERT INTO stock_financial_report
    (stock_id, report_date, revenue, net_profit, total_assets,
     total_liabilities, operating_cash_flow, roe, eps)
WITH periods AS (
    SELECT DATE '2025-09-30' AS report_date, 1 AS quarter_no
    UNION ALL SELECT DATE '2025-12-31', 2
    UNION ALL SELECT DATE '2026-03-31', 3
    UNION ALL SELECT DATE '2026-06-30', 4
),
base AS (
    SELECT s.id            AS stock_id,
           s.total_share,
           p.report_date,
           ((20 + (s.id * 7 % 40)) * 100000000::numeric
               * (0.95 + 0.03 * p.quarter_no + 0.08 * (random() - 0.5)))::numeric AS revenue,
           (0.06 + random() * 0.16)::numeric AS margin,
           (0.30 + random() * 0.35)::numeric AS equity_ratio,
           (2.0 + random() * 3.0)::numeric   AS asset_multiple,
           (random() * 1.8 - 0.4)::numeric   AS cash_factor
    FROM stock s
    CROSS JOIN periods p
),
calc AS (
    SELECT *,
           ROUND(revenue * margin, 2)          AS net_profit,
           ROUND(revenue * asset_multiple, 2)  AS total_assets
    FROM base
),
calc2 AS (
    SELECT *,
           ROUND(total_assets * equity_ratio, 2) AS equity,
           ROUND(net_profit * cash_factor, 2)    AS operating_cash_flow
    FROM calc
)
SELECT stock_id,
       report_date,
       revenue,
       net_profit,
       total_assets,
       ROUND(total_assets - equity, 2)                     AS total_liabilities,
       operating_cash_flow,
       ROUND(net_profit / NULLIF(equity, 0) * 100, 2)      AS roe,
       ROUND(net_profit / NULLIF(total_share * 10000, 0), 4) AS eps
FROM calc2;

COMMIT;

-- ============================================================
-- 数据验证 / 查询分析示例（按需取消注释执行）
-- ============================================================

-- 行数检查：预期 30 / 450 / 120
-- SELECT 'stock' AS table_name, COUNT(*) AS row_count FROM stock
-- UNION ALL SELECT 'stock_daily_quote', COUNT(*) FROM stock_daily_quote
-- UNION ALL SELECT 'stock_financial_report', COUNT(*) FROM stock_financial_report;

-- 1. 最新交易日涨幅前 5 的股票
-- SELECT q.trade_date, s.stock_code, s.stock_name, s.industry,
--        q.close_price, q.pct_change, q.turnover_rate
-- FROM stock_daily_quote q
-- JOIN stock s ON s.id = q.stock_id
-- WHERE q.trade_date = (SELECT MAX(trade_date) FROM stock_daily_quote)
-- ORDER BY q.pct_change DESC
-- LIMIT 5;

-- 2. 各行业在统计区间内的平均涨跌幅与成交额
-- SELECT s.industry,
--        COUNT(*)                           AS quote_rows,
--        ROUND(AVG(q.pct_change), 2)        AS avg_pct_change,
--        ROUND(SUM(q.amount) / 100000000, 2) AS total_amount_yi
-- FROM stock_daily_quote q
-- JOIN stock s ON s.id = q.stock_id
-- GROUP BY s.industry
-- ORDER BY avg_pct_change DESC;

-- 3. 2026 年二季度营业收入前 10 的股票
-- SELECT s.stock_code, s.stock_name, s.industry,
--        r.revenue, r.net_profit, r.roe, r.eps
-- FROM stock_financial_report r
-- JOIN stock s ON s.id = r.stock_id
-- WHERE r.report_date = DATE '2026-06-30'
-- ORDER BY r.revenue DESC
-- LIMIT 10;

-- 4. 每只股票最近 15 个交易日的累计涨跌幅（按收盘价首尾计算）
-- SELECT s.stock_code, s.stock_name,
--        MIN(q.trade_date) AS first_date,
--        MAX(q.trade_date) AS last_date,
--        ROUND((MAX(CASE WHEN q.trade_date = d.last_date THEN q.close_price END)
--             / MIN(CASE WHEN q.trade_date = d.first_date THEN q.close_price END) - 1) * 100, 2) AS period_return
-- FROM stock_daily_quote q
-- JOIN stock s ON s.id = q.stock_id
-- JOIN (SELECT stock_id, MIN(trade_date) AS first_date, MAX(trade_date) AS last_date
--       FROM stock_daily_quote GROUP BY stock_id) d ON d.stock_id = q.stock_id
-- GROUP BY s.stock_code, s.stock_name
-- ORDER BY period_return DESC;
