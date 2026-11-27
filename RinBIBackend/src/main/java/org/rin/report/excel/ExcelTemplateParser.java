package org.rin.report.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.rin.config.AgentProperties;
import org.rin.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Excel 模板的文本结构、公式与合并单元格信息，供模型推断填充位置。
 */
@Component
public class ExcelTemplateParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelTemplateParser.class);
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{\\{\\s*([^{}]{1,40}?)\\s*}}");
    private static final int MAX_CELL_TEXT_CHARS = 160;
    private static final int MAX_MERGED_REGIONS = 200;

    @Autowired
    private AgentProperties properties;

    public ExcelTemplate parse(byte[] content) {
        int maxRows = positive(properties.getReport().getMaxTemplateRows(), 200);
        int maxColumns = positive(properties.getReport().getMaxTemplateColumns(), 60);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<ExcelTemplate.Sheet> sheets = new ArrayList<>();
            int globalMaxRow = 0;
            int globalMaxColumn = 0;

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                int lastRow = Math.min(sheet.getLastRowNum(), maxRows - 1);
                int lastColumn = Math.min(maxColumn(sheet), maxColumns - 1);
                globalMaxRow = Math.max(globalMaxRow, lastRow + 1);
                globalMaxColumn = Math.max(globalMaxColumn, lastColumn + 1);

                List<ExcelTemplate.Row> rows = new ArrayList<>();
                List<ExcelTemplate.Placeholder> placeholders = new ArrayList<>();
                for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= lastRow; rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) {
                        continue;
                    }
                    List<ExcelTemplate.Cell> cells = new ArrayList<>();
                    int rowLastColumn = Math.min(row.getLastCellNum() - 1, lastColumn);
                    for (int colIndex = Math.max(row.getFirstCellNum(), 0); colIndex <= rowLastColumn; colIndex++) {
                        Cell cell = row.getCell(colIndex);
                        String text = readText(cell, formatter, evaluator);
                        String formula = readFormula(cell);
                        // 公式结果当前为空（如 IFERROR 兜底成空串）时仍要保留单元格，
                        // 否则模型看不到这一格是模板公式
                        if (text.isEmpty() && formula == null) {
                            continue;
                        }
                        String address = address(rowIndex, colIndex);
                        cells.add(ExcelTemplate.Cell.builder()
                                .col(colIndex + 1)
                                .address(address)
                                .text(text)
                                .formula(formula)
                                .build());
                        Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
                        while (matcher.find()) {
                            placeholders.add(ExcelTemplate.Placeholder.builder()
                                    .name(matcher.group(1))
                                    .sheet(sheet.getSheetName())
                                    .row(rowIndex + 1)
                                    .col(colIndex + 1)
                                    .address(address)
                                    .build());
                        }
                    }
                    if (!cells.isEmpty()) {
                        rows.add(ExcelTemplate.Row.builder().row(rowIndex + 1).cells(cells).build());
                    }
                }

                List<ExcelTemplate.Merged> merged = new ArrayList<>();
                for (CellRangeAddress region : sheet.getMergedRegions()) {
                    if (merged.size() >= MAX_MERGED_REGIONS) {
                        break;
                    }
                    merged.add(ExcelTemplate.Merged.builder()
                            .range(region.formatAsString())
                            .firstRow(region.getFirstRow() + 1)
                            .firstColumn(region.getFirstColumn() + 1)
                            .lastRow(region.getLastRow() + 1)
                            .lastColumn(region.getLastColumn() + 1)
                            .build());
                }

                sheets.add(ExcelTemplate.Sheet.builder()
                        .name(sheet.getSheetName())
                        .lastRow(lastRow + 1)
                        .lastColumn(lastColumn + 1)
                        .rows(rows)
                        .mergedRegions(merged)
                        .placeholders(placeholders)
                        .build());
            }

            if (sheets.isEmpty()) {
                throw new BadRequestException("Excel 模板不包含任何工作表");
            }
            return ExcelTemplate.builder()
                    .sheets(sheets)
                    .maxRow(globalMaxRow)
                    .maxColumn(globalMaxColumn)
                    .build();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析 Excel 模板失败: {}", e.getMessage());
            throw new BadRequestException("无法解析 Excel 模板，请确认文件为有效的 .xlsx 或 .xls 文件");
        }
    }

    /**
     * 把模板结构渲染成紧凑文本，控制长度以适配模型上下文。
     */
    public String toPromptText(ExcelTemplate template) {
        StringBuilder builder = new StringBuilder();
        for (ExcelTemplate.Sheet sheet : template.getSheets()) {
            int formulaCells = formulaCellCount(sheet);
            builder.append("工作表 \"").append(sheet.getName()).append("\"")
                    .append("（已用区域 ").append(sheet.getRows().isEmpty() ? "-" : sheet.getLastRow() + " 行")
                    .append(" / ").append(sheet.getLastColumn()).append(" 列");
            if (formulaCells > 0) {
                builder.append("，含 ").append(formulaCells).append(" 个公式单元格（模板公式，回填后自动重算）");
            }
            builder.append("）\n");
            for (ExcelTemplate.Row row : sheet.getRows()) {
                builder.append("  第").append(row.getRow()).append("行: ");
                for (int i = 0; i < row.getCells().size(); i++) {
                    ExcelTemplate.Cell cell = row.getCells().get(i);
                    if (i > 0) {
                        builder.append(" | ");
                    }
                    builder.append(cell.getAddress()).append('=')
                            .append(cell.getText().isEmpty() ? "（空）" : cell.getText());
                    if (cell.getFormula() != null) {
                        builder.append("（公式 ").append(cell.getFormula()).append("）");
                    }
                }
                builder.append('\n');
            }
            if (!sheet.getMergedRegions().isEmpty()) {
                builder.append("  合并单元格: ");
                for (int i = 0; i < sheet.getMergedRegions().size(); i++) {
                    if (i > 0) {
                        builder.append("、");
                    }
                    builder.append(sheet.getMergedRegions().get(i).getRange());
                }
                builder.append('\n');
            }
            if (!sheet.getPlaceholders().isEmpty()) {
                builder.append("  占位符: ");
                for (int i = 0; i < sheet.getPlaceholders().size(); i++) {
                    if (i > 0) {
                        builder.append("、");
                    }
                    ExcelTemplate.Placeholder placeholder = sheet.getPlaceholders().get(i);
                    builder.append(placeholder.getAddress()).append("={{")
                            .append(placeholder.getName()).append("}}");
                }
                builder.append('\n');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private String readText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        String text;
        try {
            text = cell.getCellType() == CellType.FORMULA
                    ? formatter.formatCellValue(cell, evaluator)
                    : formatter.formatCellValue(cell);
        } catch (Exception e) {
            // 公式引用异常时不阻断模板解析，保留公式原文提示模型
            log.debug("模板单元格取值失败，改为保留公式原文: {}", e.getMessage());
            text = readFormula(cell);
        }
        if (text == null) {
            return "";
        }
        text = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (text.length() > MAX_CELL_TEXT_CHARS) {
            text = text.substring(0, MAX_CELL_TEXT_CHARS) + "…";
        }
        return text;
    }

    /**
     * 读取公式原文（统一补上前导 =），非公式单元格或读取失败时返回 null。
     *
     * <p>公式单元格的 {@code text} 只是计算结果，模型看不到“这一格是算出来的”，
     * 容易把汇总公式当成普通数据格覆盖掉，因此这里单独保留公式原文用于提示词。</p>
     */
    private String readFormula(Cell cell) {
        if (cell == null || cell.getCellType() != CellType.FORMULA) {
            return null;
        }
        try {
            String formula = cell.getCellFormula();
            if (formula == null || formula.isBlank()) {
                return null;
            }
            String normalized = formula.startsWith("=") ? formula : "=" + formula;
            return normalized.length() > MAX_CELL_TEXT_CHARS
                    ? normalized.substring(0, MAX_CELL_TEXT_CHARS) + "…"
                    : normalized;
        } catch (RuntimeException e) {
            // 公式损坏（外部引用失效、加密等）时不影响模板解析
            log.debug("读取模板公式失败: {}", e.getMessage());
            return null;
        }
    }

    private int formulaCellCount(ExcelTemplate.Sheet sheet) {
        int count = 0;
        for (ExcelTemplate.Row row : sheet.getRows()) {
            for (ExcelTemplate.Cell cell : row.getCells()) {
                if (cell.getFormula() != null) {
                    count++;
                }
            }
        }
        return count;
    }

    private int maxColumn(Sheet sheet) {
        int max = 0;
        for (Row row : sheet) {
            max = Math.max(max, row.getLastCellNum());
        }
        return max;
    }

    private String address(int rowIndex, int colIndex) {
        return new CellReference(rowIndex, colIndex).formatAsString();
    }

    private int positive(Integer value, int fallback) {
        return value == null || value <= 0 ? fallback : value;
    }
}
