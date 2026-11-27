package org.rin.report.excel;

import org.apache.poi.hssf.usermodel.HSSFEvaluationWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.formula.FormulaParser;
import org.apache.poi.ss.formula.FormulaParsingWorkbook;
import org.apache.poi.ss.formula.FormulaRenderer;
import org.apache.poi.ss.formula.FormulaRenderingWorkbook;
import org.apache.poi.ss.formula.FormulaShifter;
import org.apache.poi.ss.formula.FormulaType;
import org.apache.poi.ss.formula.ptg.Ptg;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFEvaluationWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.rin.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于 POI 把已校验的数据写入 Excel 模板。
 *
 * <p>表格区域会按数据行数扩展：先复制模板数据行的样式，再把下方行整体下移。
 * 模板数据行中的横向合并单元格与公式会复制到每一行数据（公式中的相对引用按行偏移平移）。
 * 所有坐标均为 1-based。</p>
 */
@Component
public class ExcelReportFiller {

    private static final Logger log = LoggerFactory.getLogger(ExcelReportFiller.class);
    private static final int MAX_CELL_CHARS = 32767;
    private static final Pattern PLAIN_NUMBER =
            Pattern.compile("^-?(0|[1-9]\\d{0,14})(\\.\\d+)?$");

    public byte[] fill(byte[] templateBytes, ReportFillPlan plan) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(templateBytes))) {
            List<ReportFillPlan.TableValue> tables = plan.getTables() == null
                    ? new ArrayList<>() : new ArrayList<>(plan.getTables());
            // 从下往上填表：上方表格扩展行数时，下方已填好的表格会随之整体下移
            tables.sort(Comparator.comparingInt(ReportFillPlan.TableValue::getStartRow).reversed());
            for (ReportFillPlan.TableValue table : tables) {
                writeTable(workbook, table);
            }
            if (plan.getCells() != null) {
                for (ReportFillPlan.CellValue cell : plan.getCells()) {
                    writeCell(workbook, cell, tables);
                }
            }
            recalculateFormulas(workbook);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.warn("回填 Excel 模板失败: {}", e.getMessage());
            throw new BadRequestException("回填 Excel 模板失败：" + safeMessage(e));
        }
    }

    /**
     * 主动重算模板中的公式。
     *
     * <p>POI 只是把值写进单元格，不会像 Excel 那样自动重算：模板里已有的公式单元格
     * 会继续保留写入文件时的缓存结果（合计、占比等会停留在旧数据上）。
     * {@code setForceFormulaRecalculation(true)} 只是在文件里打标记，让 Excel
     * 打开时重算一次，对不重算的查看器（预览、其他语言的读取库）无效，
     * 因此这里再用 {@link FormulaEvaluator} 算一遍，把结果写回缓存值。</p>
     */
    private void recalculateFormulas(Workbook workbook) {
        // 兜底：POI 不支持的函数、跨工作簿引用只能交给 Excel 打开时重算
        workbook.setForceFormulaRecalculation(true);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        evaluator.clearAllCachedResultValues();
        try {
            evaluator.evaluateAll();
            return;
        } catch (RuntimeException e) {
            // 批量重算遇到不支持的公式会直接中断，改为逐单元格重算，跳过算不出来的格子
            log.debug("批量重算模板公式失败，改为逐单元格重算: {}", safeMessage(e));
        }
        int failed = 0;
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            for (Row row : workbook.getSheetAt(sheetIndex)) {
                for (Cell cell : row) {
                    if (cell.getCellType() != CellType.FORMULA) {
                        continue;
                    }
                    try {
                        evaluator.evaluateFormulaCell(cell);
                    } catch (RuntimeException e) {
                        failed++;
                        log.debug("单元格公式无法重算，保留给 Excel 处理: sheet={}, cell={}, error={}",
                                workbook.getSheetName(sheetIndex),
                                cell.getAddress().formatAsString(), safeMessage(e));
                    }
                }
            }
        }
        if (failed > 0) {
            log.warn("有 {} 个模板公式 POI 无法重算，已标记强制重算，由 Excel 打开时计算", failed);
        }
    }

    private void writeTable(Workbook workbook, ReportFillPlan.TableValue table) {
        Sheet sheet = resolveSheet(workbook, table.getSheet());
        int startRow = table.getStartRow() - 1;
        int startCol = table.getStartCol() - 1;
        if (startRow < 0 || startCol < 0) {
            throw new BadRequestException("表格映射坐标无效：" + table.getSheet()
                    + " startRow=" + table.getStartRow() + ", startCol=" + table.getStartCol());
        }
        List<List<String>> rows = table.getRows() == null ? new ArrayList<>() : table.getRows();
        int rowCount = rows.size();
        Row templateRow = sheet.getRow(startRow);
        short templateHeight = templateRow == null ? -1 : templateRow.getHeight();

        // 记录模板数据行的横向合并，稍后复制到每一行数据
        List<CellRangeAddress> rowMerges = new ArrayList<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            if (region.getFirstRow() == startRow && region.getLastRow() == startRow
                    && region.getLastColumn() > region.getFirstColumn()) {
                rowMerges.add(region);
            }
        }

        if (rowCount > 1) {
            expandRows(sheet, startRow, rowCount - 1);
        }

        if (table.getHeaderRow() != null && table.getHeaders() != null
                && !table.getHeaders().isEmpty()) {
            Row header = getOrCreateRow(sheet, table.getHeaderRow() - 1);
            for (int i = 0; i < table.getHeaders().size(); i++) {
                writeValue(getOrCreateCell(header, startCol + i), table.getHeaders().get(i));
            }
        }

        for (int rowOffset = 0; rowOffset < rowCount; rowOffset++) {
            Row row = getOrCreateRow(sheet, startRow + rowOffset);
            if (templateHeight >= 0) {
                row.setHeight(templateHeight);
            }
            List<String> values = rows.get(rowOffset);
            for (int colOffset = 0; values != null && colOffset < values.size(); colOffset++) {
                Cell cell = getOrCreateCell(row, startCol + colOffset);
                if (rowOffset > 0 && templateRow != null) {
                    Cell source = templateRow.getCell(startCol + colOffset);
                    if (source != null) {
                        cell.setCellStyle(source.getCellStyle());
                    }
                }
                writeValue(cell, values.get(colOffset));
            }
        }

        // 模板数据行的公式（如“客单价 = 销售金额 / 订单数量”）复制到扩展出来的每一行
        if (rowCount > 1 && templateRow != null) {
            copyRowFormulas(workbook, sheet, templateRow, startRow, startCol, rows);
        }

        // 复制模板数据行的横向合并区域
        if (rowCount > 1 && !rowMerges.isEmpty()) {
            List<CellRangeAddress> merged = new ArrayList<>(sheet.getMergedRegions());
            for (int rowOffset = 1; rowOffset < rowCount; rowOffset++) {
                for (CellRangeAddress source : rowMerges) {
                    CellRangeAddress candidate = new CellRangeAddress(startRow + rowOffset,
                            startRow + rowOffset, source.getFirstColumn(), source.getLastColumn());
                    if (intersects(merged, candidate)) {
                        continue;
                    }
                    sheet.addMergedRegion(candidate);
                    merged.add(candidate);
                }
            }
        }
    }

    /**
     * 把模板数据行里的公式复制到表格扩展出来的每一行。
     *
     * <p>模板作者写在数据行里的公式只存在于模板的那一行，行扩展时新行只会复制样式，
     * 因此这里按行偏移平移公式中的相对引用后逐行写入（$A$1 这类绝对引用保持不变）。
     * 同一单元格若已由数据写入值，则以数据为准，不被公式覆盖。</p>
     */
    private void copyRowFormulas(Workbook workbook, Sheet sheet, Row templateRow,
                                 int startRow, int startCol, List<List<String>> rows) {
        for (int rowOffset = 1; rowOffset < rows.size(); rowOffset++) {
            List<String> values = rows.get(rowOffset);
            int valueWidth = values == null ? 0 : values.size();
            Row row = getOrCreateRow(sheet, startRow + rowOffset);
            for (Cell source : templateRow) {
                if (source.getCellType() != CellType.FORMULA) {
                    continue;
                }
                int colIndex = source.getColumnIndex();
                if (colIndex >= startCol && colIndex - startCol < valueWidth) {
                    // 该列由数据行写入，公式让位于数据
                    continue;
                }
                String shifted = shiftFormula(workbook, sheet, source, startRow, rowOffset);
                if (shifted == null) {
                    continue;
                }
                Cell target = getOrCreateCell(row, colIndex);
                target.setCellStyle(source.getCellStyle());
                try {
                    target.setCellFormula(shifted);
                } catch (RuntimeException e) {
                    target.setBlank();
                    log.debug("复制模板公式失败，保留空单元格: sheet={}, cell={}, error={}",
                            sheet.getSheetName(), source.getAddress().formatAsString(),
                            safeMessage(e));
                }
            }
        }
    }

    /**
     * 按行偏移平移公式里的相对引用，返回平移后的公式原文；无法解析时返回 null。
     *
     * <p>使用 POI 的 {@link FormulaShifter#createForRowCopy}（与 Excel 向下拖拽填充一致），
     * 相对引用随行号平移、绝对引用不动。</p>
     */
    private String shiftFormula(Workbook workbook, Sheet sheet, Cell source,
                                int templateRowIndex, int rowOffset) {
        String formula;
        try {
            formula = source.getCellFormula();
        } catch (RuntimeException e) {
            log.debug("读取模板公式失败: sheet={}, cell={}, error={}",
                    sheet.getSheetName(), source.getAddress().formatAsString(), safeMessage(e));
            return null;
        }
        if (formula == null || formula.isBlank()) {
            return null;
        }
        int sheetIndex = workbook.getSheetIndex(sheet);
        try {
            FormulaParsingWorkbook evaluationWorkbook = evaluationWorkbook(workbook);
            Ptg[] tokens = FormulaParser.parse(formula, evaluationWorkbook, FormulaType.CELL, sheetIndex);
            FormulaShifter shifter = FormulaShifter.createForRowCopy(sheetIndex, sheet.getSheetName(),
                    templateRowIndex, templateRowIndex, rowOffset, workbook.getSpreadsheetVersion());
            shifter.adjustFormula(tokens, sheetIndex);
            return FormulaRenderer.toFormulaString((FormulaRenderingWorkbook) evaluationWorkbook, tokens);
        } catch (RuntimeException e) {
            // 跨工作簿引用、结构化引用等 POI 解析不了的公式：跳过复制，交给模板原行
            log.debug("模板公式无法平移，跳过复制: sheet={}, cell={}, formula={}, error={}",
                    sheet.getSheetName(), source.getAddress().formatAsString(), formula, safeMessage(e));
            return null;
        }
    }

    private FormulaParsingWorkbook evaluationWorkbook(Workbook workbook) {
        if (workbook instanceof XSSFWorkbook) {
            return XSSFEvaluationWorkbook.create((XSSFWorkbook) workbook);
        }
        return HSSFEvaluationWorkbook.create((HSSFWorkbook) workbook);
    }

    /**
     * 把数据行以下的内容整体下移，并同步移动这些行上的合并区域。
     */
    private void expandRows(Sheet sheet, int startRow, int extraRows) {
        int lastRow = sheet.getLastRowNum();
        int firstBelow = startRow + 1;
        if (lastRow < firstBelow) {
            return;
        }
        List<CellRangeAddress> shifted = new ArrayList<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            if (region.getFirstRow() > startRow) {
                shifted.add(new CellRangeAddress(region.getFirstRow() + extraRows,
                        region.getLastRow() + extraRows,
                        region.getFirstColumn(), region.getLastColumn()));
            }
        }
        removeMergedRegions(sheet, region -> region.getFirstRow() > startRow);
        sheet.shiftRows(firstBelow, lastRow, extraRows, true, false);
        for (CellRangeAddress region : shifted) {
            sheet.addMergedRegion(region);
        }
    }


    private void writeCell(Workbook workbook, ReportFillPlan.CellValue cell,
                           List<ReportFillPlan.TableValue> tables) {
        Sheet sheet = resolveSheet(workbook, cell.getSheet());
        if (cell.getRow() < 1 || cell.getCol() < 1) {
            throw new BadRequestException("单元格映射坐标无效：" + cell.getSheet()
                    + " row=" + cell.getRow() + ", col=" + cell.getCol());
        }
        int targetRow = cell.getRow() - 1;
        // 单元格位于已扩展表格下方时，需要跟随表格下移
        for (ReportFillPlan.TableValue table : tables) {
            if (table.getSheet() != null && table.getSheet().equalsIgnoreCase(cell.getSheet())
                    && table.getStartRow() < cell.getRow()) {
                int added = table.getRows() == null ? 0 : Math.max(0, table.getRows().size() - 1);
                targetRow += added;
            }
        }
        Row row = getOrCreateRow(sheet, targetRow);
        writeValue(getOrCreateCell(row, cell.getCol() - 1), cell.getValue());
    }

    private Sheet resolveSheet(Workbook workbook, String name) {
        if (name == null || name.isBlank()) {
            return workbook.getSheetAt(0);
        }
        Sheet sheet = workbook.getSheet(name);
        if (sheet != null) {
            return sheet;
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet candidate = workbook.getSheetAt(i);
            if (candidate.getSheetName().equalsIgnoreCase(name.trim())) {
                return candidate;
            }
        }
        throw new BadRequestException("Excel 模板中不存在工作表：" + name);
    }

    private Row getOrCreateRow(Sheet sheet, int rowIndex) {
        if (rowIndex < 0) {
            throw new BadRequestException("Excel 行号无效：" + (rowIndex + 1));
        }
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        return row;
    }

    private Cell getOrCreateCell(Row row, int colIndex) {
        if (colIndex < 0) {
            throw new BadRequestException("Excel 列号无效：" + (colIndex + 1));
        }
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            cell = row.createCell(colIndex);
        }
        return cell;
    }

    private void writeValue(Cell cell, String value) {
        // 同一位置在模板里可能是公式：写入数据前先移除公式，
        // 否则后续重算会把公式结果覆盖掉已写入的数据
        if (cell.getCellType() == CellType.FORMULA) {
            cell.removeFormula();
        }
        if (value == null || value.isEmpty()) {
            cell.setBlank();
            return;
        }
        String text = value.length() > MAX_CELL_CHARS ? value.substring(0, MAX_CELL_CHARS) : value;
        if (PLAIN_NUMBER.matcher(text).matches()) {
            try {
                BigDecimal number = new BigDecimal(text);
                if (number.abs().compareTo(BigDecimal.valueOf(1_000_000_000_000_000L)) <= 0) {
                    cell.setCellValue(number.doubleValue());
                    return;
                }
            } catch (NumberFormatException e) {
                // 落到字符串写入
                log.debug("单元格数值转换失败，按字符串写入: value={}", text);
            }
        }
        cell.setCellValue(text);
    }

    private boolean intersects(List<CellRangeAddress> regions, CellRangeAddress candidate) {
        for (CellRangeAddress region : regions) {
            if (region.intersects(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void removeMergedRegions(Sheet sheet, java.util.function.Predicate<CellRangeAddress> predicate) {
        List<CellRangeAddress> current = new ArrayList<>(sheet.getMergedRegions());
        Set<String> targets = new HashSet<>();
        for (CellRangeAddress region : current) {
            if (predicate.test(region)) {
                targets.add(key(region));
            }
        }
        for (int i = current.size() - 1; i >= 0; i--) {
            if (targets.contains(key(current.get(i)))) {
                sheet.removeMergedRegion(i);
            }
        }
    }

    private String key(CellRangeAddress region) {
        return region.getFirstRow() + ":" + region.getFirstColumn()
                + ":" + region.getLastRow() + ":" + region.getLastColumn();
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }
}


