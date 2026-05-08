package inv;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MiddleSpecialHighScraper {

    private static final String BASE_URL = "https://apt2.me/apt/middle.jsp";
    private static final String YEAR = "2025";

    // 전국 시도 코드
    private static final List<String> AREA_CODES = List.of(
            "11", // 서울
            "26", // 부산
            "27", // 대구
            "28", // 인천
            "29", // 광주
            "30", // 대전
            "31", // 울산
            "36", // 세종
            "41", // 경기
            "42", // 강원
            "43", // 충북
            "44", // 충남
            "45", // 전북
            "46", // 전남
            "47", // 경북
            "48", // 경남
            "50"  // 제주
    );

    public static void main(String[] args) throws Exception {
        Map<String, SchoolSpecialHigh> resultMap = new LinkedHashMap<>();

        for (String area : AREA_CODES) {
            int page = 1;

            while (true) {
                String url = buildUrl(area, page);
                System.out.println("CALL : " + url);

                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0")
                        .timeout(15000)
                        .get();

                List<SchoolSpecialHigh> list = parsePage(doc);

                if (list.isEmpty()) {
                    System.out.println("No data. Stop area=" + area + ", page=" + page);
                    break;
                }

                for (SchoolSpecialHigh item : list) {
                    String key = item.schoolName + "|" + item.address;
                    resultMap.putIfAbsent(key, item);
                }

                page++;

                // 서버 부하 방지
                Thread.sleep(300);
            }
        }

        writeExcel(new ArrayList<>(resultMap.values()), "middle_special_high_result.xlsx");
    }

    private static String buildUrl(String area, int page) {
        return BASE_URL
                + "?area=" + area
                + "&pages=" + page
                + "&Cmb_year=" + YEAR;
    }

    private static List<SchoolSpecialHigh> parsePage(Document doc) {
        List<SchoolSpecialHigh> result = new ArrayList<>();

        Elements rows = doc.select("tr");

        for (Element row : rows) {
            Elements tds = row.select("td.td_style1");

            // 데이터 row는 보통 3개 td
            // 0: 학교명/주소
            // 1: 과고/외고국제고/자사고/기타영재고
            // 2: 총인원/특목고계/비율
            if (tds.size() < 3) {
                continue;
            }

            Element schoolTd = tds.get(0);
            Element totalTd = tds.get(2);

            Element schoolNameEl = schoolTd.selectFirst("span[style*=font-weight:bold]");
            if (schoolNameEl == null) {
                schoolNameEl = schoolTd.selectFirst("span");
            }

            if (schoolNameEl == null) {
                continue;
            }

            String schoolName = clean(schoolNameEl.text());
            String address = extractAddress(schoolTd);
            String specialHighCount = extractSpecialHighCount(totalTd);

            if (schoolName.isBlank() || address.isBlank() || specialHighCount.isBlank()) {
                continue;
            }

            SchoolSpecialHigh item = new SchoolSpecialHigh();
            item.address = address;
            item.schoolName = schoolName;
            item.specialHighCount = specialHighCount;

            result.add(item);
        }

        return result;
    }

    private static String extractAddress(Element schoolTd) {
        List<String> lines = schoolTd.html()
                .replaceAll("(?i)<br[^>]*>", "\n")
                .replace("&nbsp;", " ")
                .lines()
                .map(line -> Jsoup.parse(line).text())
                .map(MiddleSpecialHighScraper::clean)
                .filter(s -> !s.isBlank())
                .toList();

        for (String line : lines) {
            if (line.contains("년도별실적")) {
                continue;
            }

            if (line.contains("성취도")) {
                continue;
            }

            // 주소로 보이는 라인
            if (isAddressLine(line)) {
                return line;
            }
        }

        return "";
    }

    private static boolean isAddressLine(String line) {
        return line.contains("서울")
                || line.contains("부산")
                || line.contains("대구")
                || line.contains("인천")
                || line.contains("광주")
                || line.contains("대전")
                || line.contains("울산")
                || line.contains("세종")
                || line.contains("경기")
                || line.contains("강원")
                || line.contains("충북")
                || line.contains("충남")
                || line.contains("전북")
                || line.contains("전남")
                || line.contains("경북")
                || line.contains("경남")
                || line.contains("제주")
                || line.contains("특별시")
                || line.contains("광역시")
                || line.contains("특별자치시")
                || line.contains("특별자치도")
                || line.contains("도 ");
    }

    private static String extractSpecialHighCount(Element totalTd) {
        // 예:
        // 163 / 77 / 86
        // 46 명
        // 28.22 %
        List<String> lines = totalTd.html()
                .replaceAll("(?i)<br[^>]*>", "\n")
                .replace("&nbsp;", " ")
                .lines()
                .map(line -> Jsoup.parse(line).text())
                .map(MiddleSpecialHighScraper::clean)
                .filter(s -> !s.isBlank())
                .toList();

        for (String line : lines) {
            // 특목고계는 "46 명" 형태
            if (line.matches("^\\d+\\s*명$")) {
                return line.replace("명", "").trim();
            }
        }

        // fallback: 전체 텍스트에서 숫자 명 패턴 찾기
        String text = clean(totalTd.text());
        Matcher matcher = Pattern.compile("(\\d+)\\s*명").matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return "";
    }

    private static void writeExcel(List<SchoolSpecialHigh> list, String fileName) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("특목고 진학현황");

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        String[] headers = {"주소", "학교명", "특목고계"};

        Row header = sheet.createRow(0);

        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;

        for (SchoolSpecialHigh item : list) {
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(item.address);
            row.createCell(1).setCellValue(item.schoolName);
            row.createCell(2).setCellValue(item.specialHighCount);
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            workbook.write(fos);
        }

        workbook.close();

        File file = new File(fileName);
        System.out.println("SAVE PATH : " + file.getAbsolutePath());
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\u00A0", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static class SchoolSpecialHigh {
        String address = "";
        String schoolName = "";
        String specialHighCount = "";
    }
}