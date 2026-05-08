package com.sample;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MiddleGradeScraper {

    private static final String BASE_URL = "https://apt2.me/apt/middleGrade.jsp";

    private static final String YEAR = "2025";
    private static final String GRADE = "3";
    private static final String TERM = "2";
    private static final String AREA = "00";

    private static final List<String> SUBJECTS = List.of("국어", "영어", "수학");

    public static void main(String[] args) throws Exception {
        Map<String, SchoolScore> resultMap = new LinkedHashMap<>();

        for (String subject : SUBJECTS) {
            int page = 1;

            while (true) {
                String url = buildUrl(page, subject);
                System.out.println("CALL : " + url);

                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0")
                        .timeout(10000)
                        .get();

                List<SchoolScore> list = parsePage(doc, subject);

                if (list.isEmpty()) {
                    System.out.println("No data. Stop subject=" + subject + ", page=" + page);
                    break;
                }

                for (SchoolScore item : list) {
                    String key = item.schoolName + "|" + item.address;

                    SchoolScore saved = resultMap.getOrDefault(key, new SchoolScore());
                    saved.address = item.address;
                    saved.schoolName = item.schoolName;
                    saved.schoolType = item.schoolType;

                    if ("국어".equals(subject)) {
                        saved.korean = item.korean;
                    } else if ("영어".equals(subject)) {
                        saved.english = item.english;
                    } else if ("수학".equals(subject)) {
                        saved.math = item.math;
                    }

                    resultMap.put(key, saved);
                }

                page++;

                // 서버 부하 방지용
                Thread.sleep(300);
            }
        }

        writeExcel(new ArrayList<>(resultMap.values()), "middle_grade_result.xlsx");

        System.out.println("Excel created: middle_grade_result.xlsx");
    }

    private static String buildUrl(int page, String subject) {
        return BASE_URL
                + "?pages=" + page
                + "&area=" + encode(AREA)
                + "&Cmb_year=" + encode(YEAR)
                + "&Cmb_grade=" + encode(GRADE)
                + "&Cmb_term=" + encode(TERM)
                + "&Cmb_subject=" + encode(subject);
    }

    private static List<SchoolScore> parsePage(Document doc, String subject) {
        List<SchoolScore> result = new ArrayList<>();

        Elements rows = doc.select("tr");

        for (Element row : rows) {
            Elements tds = row.select("td.td_style1");

            // 데이터 row는 보통 3개 td 구조
            // 1번 td: 학교명/분류/과목/주소
            // 2번 td: 평균/표준편차
            // 3번 td: 성취도 분포
            if (tds.size() < 2) {
                continue;
            }

            Element schoolTd = tds.get(0);
            Element scoreTd = tds.get(1);

            Element schoolNameEl = schoolTd.selectFirst("span");
            Element linkEl = schoolTd.selectFirst("a");

            if (schoolNameEl == null || linkEl == null) {
                continue;
            }

            String schoolName = clean(schoolNameEl.text());

            // 예: "선인국제중학교 사립"
            String linkText = clean(linkEl.text());
            String schoolType = linkText.replace(schoolName, "").trim();

            // td 전체 텍스트 예:
            // 선인국제중학교 사립 국어 경상남도 진주시 대곡면 월암리 특목고실적
            List<String> lines = schoolTd.html()
                    .replaceAll("(?i)<br[^>]*>", "\n")
                    .replaceAll("&nbsp;", " ")
                    .lines()
                    .map(line -> Jsoup.parse(line).text())
                    .map(MiddleGradeScraper::clean)
                    .filter(s -> !s.isBlank())
                    .toList();

            String address = "";

            for (String line : lines) {
                if (line.contains("특목고실적")) {
                    continue;
                }

                if (line.equals(subject)) {
                    continue;
                }

                if (line.contains("도 ") || line.contains("시 ") || line.contains("군 ") || line.contains("구 ")) {
                    if (!line.contains(schoolName)) {
                        address = line;
                        break;
                    }
                }
            }

            String scoreText = clean(scoreTd.text());
            String score = "";

            // 예: "97.6 0" → 평균만 추출
            String[] scoreParts = scoreText.split("\\s+");
            if (scoreParts.length > 0) {
                score = scoreParts[0];
            }

            if (schoolName.isBlank() || address.isBlank() || score.isBlank()) {
                continue;
            }

            SchoolScore item = new SchoolScore();
            item.address = address;
            item.schoolName = schoolName;
            item.schoolType = schoolType;

            if ("국어".equals(subject)) {
                item.korean = score;
            } else if ("영어".equals(subject)) {
                item.english = score;
            } else if ("수학".equals(subject)) {
                item.math = score;
            }

            result.add(item);
        }

        return result;
    }

    private static void writeExcel(List<SchoolScore> list, String fileName) throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("중학교 성취도");

        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        Row header = sheet.createRow(0);
        String[] headers = {"주소", "학교명", "분류", "국어", "영어", "수학", "평균"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIdx = 1;

        for (SchoolScore item : list) {
            Row row = sheet.createRow(rowIdx++);

            row.createCell(0).setCellValue(item.address);
            row.createCell(1).setCellValue(item.schoolName);
            row.createCell(2).setCellValue(item.schoolType);
            row.createCell(3).setCellValue(item.korean);
            row.createCell(4).setCellValue(item.english);
            row.createCell(5).setCellValue(item.math);
            row.createCell(6).setCellValue(calcAverage(item));
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            workbook.write(fos);
        }

        workbook.close();
    }

    private static String calcAverage(SchoolScore item) {
        List<Double> scores = new ArrayList<>();

        addScore(scores, item.korean);
        addScore(scores, item.english);
        addScore(scores, item.math);

        if (scores.isEmpty()) {
            return "";
        }

        double avg = scores.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0);

        return String.format("%.1f", avg);
    }

    private static void addScore(List<Double> scores, String value) {
        try {
            if (value != null && !value.isBlank()) {
                scores.add(Double.parseDouble(value));
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    static class SchoolScore {
        String address = "";
        String schoolName = "";
        String schoolType = "";
        String korean = "";
        String english = "";
        String math = "";
    }
}