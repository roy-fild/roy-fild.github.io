package inv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;

/**
 * 실행 예:
 *   java ExcelToJson "C:\\data\\apt_list.xlsx"
 * → 결과: C:\data\apt_list.json 자동 생성
 */
public class ExcelToJson {

    public static void main(String[] args) throws Exception {
//        if (args.length != 1) {
//            System.out.println("Usage: java ExcelToJson <excel_full_path>");
//            return;
//        }

        String excelPath = "C:\\project\\inv\\prev-high_20251027.xlsx";
        File inputFile = new File(excelPath);
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("File not found: " + excelPath);
        }

        // 같은 경로, 같은 파일명으로 json 파일 생성
        String outputPath = excelPath.replaceAll("\\.xlsx?$", "") + ".json";

        Map<String, Object> result = convertExcelToJson(excelPath);

        ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            om.writeValue(fos, result);
        }

        System.out.println("✔ JSON file created at: " + outputPath);
    }

    /** 엑셀을 읽어 ID별 합쳐진 JSON 구조 맵으로 반환 */
    public static Map<String, Object> convertExcelToJson(String excelPath) throws Exception {
        Map<String, Object> idMap = new LinkedHashMap<>();

        try (FileInputStream fis = new FileInputStream(excelPath);
             Workbook wb = WorkbookFactory.create(fis)) {

            Sheet sheet = wb.getSheetAt(0); // 첫 번째 시트 기준

            Map<String, Integer> idx = headerIndex(sheet.getRow(sheet.getFirstRowNum()));
            requireHeaders(idx, "ID", "SI", "GU", "DONG", "NAME", "YEAR", "SD", "TP", "PREV");

            int first = sheet.getFirstRowNum() + 1;
            int last = sheet.getLastRowNum();

            for (int r = first; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                String id = asString(row.getCell(idx.get("ID")));
                if (id == null || id.isBlank()) continue;

                String si = asString(row.getCell(idx.get("SI")));
                String gu = asString(row.getCell(idx.get("GU")));
                String dong = asString(row.getCell(idx.get("DONG")));
                String name = asString(row.getCell(idx.get("NAME")));
                Integer year = asInteger(row.getCell(idx.get("YEAR")));
                Integer sd = asInteger(row.getCell(idx.get("SD")));

                String tp = asString(row.getCell(idx.get("TP")));
                Double prev = asDouble(row.getCell(idx.get("PREV")));

                @SuppressWarnings("unchecked")
                Map<String, Object> rec = (Map<String, Object>) idMap.computeIfAbsent(id, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("SI", si);
                    m.put("GU", gu);
                    m.put("DONG", dong);
                    m.put("NAME", name);
                    m.put("YEAR", year);
                    m.put("SD", sd);
                    m.put("TP", new LinkedHashMap<String, Double>());
                    return m;
                });

                @SuppressWarnings("unchecked")
                Map<String, Double> tpMap = (Map<String, Double>) rec.get("TP");
                if (tp != null && !tp.isBlank() && prev != null)
                    tpMap.put(tp, prev);
            }
        }
        return idMap;
    }

    // ====== 헬퍼 메서드 ======

    private static Map<String, Integer> headerIndex(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        if (headerRow == null) return map;
        short first = headerRow.getFirstCellNum();
        short last = headerRow.getLastCellNum();
        for (short c = first; c < last; c++) {
            Cell cell = headerRow.getCell(c);
            String key = asString(cell);
            if (key != null) map.put(key.trim(), (int) c);
        }
        return map;
    }

    private static void requireHeaders(Map<String, Integer> idx, String... keys) {
        List<String> missing = new ArrayList<>();
        for (String k : keys) if (!idx.containsKey(k)) missing.add(k);
        if (!missing.isEmpty())
            throw new IllegalArgumentException("Missing headers: " + missing);
    }

    private static String asString(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                double v = cell.getNumericCellValue();
                return (v == Math.floor(v)) ? String.valueOf((long)v) : String.valueOf(v);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try { return cell.getStringCellValue().trim(); }
                catch (Exception e) {
                    try {
                        double n = cell.getNumericCellValue();
                        return (n == Math.floor(n)) ? String.valueOf((long)n) : String.valueOf(n);
                    } catch (Exception ex) { return null; }
                }
            default: return null;
        }
    }

    private static Integer asInteger(Cell cell) {
        try { return (int)Double.parseDouble(asString(cell)); } catch (Exception e) { return null; }
    }

    private static Double asDouble(Cell cell) {
        try { return Double.parseDouble(asString(cell)); } catch (Exception e) { return null; }
    }
}
