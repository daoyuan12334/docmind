package com.docmind.ingest.parser;

import com.docmind.ingest.DocumentParser;
import com.docmind.ingest.ParsedDocument;
import com.docmind.kb.KbModels;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 表格解析：每个 Sheet 转为 Markdown 表格，并按行窗口切块，
 * 保证检索时能命中整行语义（例如「张三 2025 年报销金额」）。
 */
@Component
public class SheetDocumentParser implements DocumentParser {

    private static final int ROWS_PER_BLOCK = 40;

    @Override
    public boolean supports(KbModels.Modality modality, String ext) {
        return modality == KbModels.Modality.SHEET
                || (ext != null && ("xlsx".equalsIgnoreCase(ext) || "xls".equalsIgnoreCase(ext)));
    }

    @Override
    public ParsedDocument parse(KbModels.Document doc, byte[] bytes) throws Exception {
        ParsedDocument parsed = new ParsedDocument().modality(KbModels.Modality.SHEET);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter();
            int sheetIndex = 0;
            for (Sheet sheet : wb) {
                sheetIndex++;
                List<String> header = new ArrayList<>();
                List<List<String>> rows = new ArrayList<>();
                boolean headerDone = false;
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(formatter.formatCellValue(cell).trim());
                    }
                    while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                        cells.remove(cells.size() - 1);
                    }
                    if (cells.isEmpty()) {
                        continue;
                    }
                    if (!headerDone) {
                        header = cells;
                        headerDone = true;
                        continue;
                    }
                    rows.add(cells);
                }
                if (header.isEmpty() && rows.isEmpty()) {
                    continue;
                }
                // 标题行单独成块，便于检索命中列名
                if (!header.isEmpty()) {
                    parsed.add("工作表[" + sheet.getSheetName() + "] 列名：" + String.join(" | ", header),
                            sheetIndex, ParsedDocument.BlockType.TABLE);
                }
                for (int i = 0; i < rows.size(); i += ROWS_PER_BLOCK) {
                    List<List<String>> window = rows.subList(i, Math.min(rows.size(), i + ROWS_PER_BLOCK));
                    parsed.add(toMarkdown(header, window), sheetIndex, ParsedDocument.BlockType.TABLE);
                }
            }
            parsed.pageCount(sheetIndex);
        }
        return parsed;
    }

    private String toMarkdown(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        if (!header.isEmpty()) {
            sb.append("| ").append(String.join(" | ", header)).append(" |\n");
            sb.append("|").append("---|".repeat(header.size())).append('\n');
        }
        for (List<String> row : rows) {
            sb.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        return sb.toString();
    }
}
