package inv;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

public class AptginMonthPriceCrawler {

    private static final String PAGE = "https://aptgin.com/home/gin01/gin0101/loc/";
    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final LinkedHashMap<String, String> PROVINCES = new LinkedHashMap<>();
    static {
        PROVINCES.put("서울", "1100000000");
        PROVINCES.put("광주", "1200000000");
        PROVINCES.put("부산", "2600000000");
        PROVINCES.put("대구", "2700000000");
        PROVINCES.put("인천", "2800000000");
        PROVINCES.put("대전", "3000000000");
        PROVINCES.put("울산", "3100000000");
        PROVINCES.put("세종", "3600000000");
        PROVINCES.put("경기", "4100000000");
        PROVINCES.put("충북", "4300000000");
        PROVINCES.put("충남", "4400000000");
        PROVINCES.put("경북", "4700000000");
        PROVINCES.put("경남", "4800000000");
        PROVINCES.put("제주", "5000000000");
        PROVINCES.put("강원 ", "5100000000");
        PROVINCES.put("전북", "5200000000");
    }

    private record MarketRow(
            String region, String area, Integer people, Integer demand,
            Integer moveIn, Integer sale, Integer jeonse, Integer ratio
    ) {}

    public static void main(String[] args) throws Exception {

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--window-size=1800,1200");
        options.addArguments("--disable-gpu");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--lang=ko-KR");

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, WAIT);

        List<MarketRow> allRows = new ArrayList<>();

        try {
            for (Map.Entry<String, String> e : PROVINCES.entrySet()) {
                String region = e.getKey();
                String code = e.getValue();

                System.out.println("[" + region + "] 스크래핑 시작");

                driver.get(PAGE + code);

                wait.until(d -> ((JavascriptExecutor) d)
                        .executeScript("return document.readyState").equals("complete"));

                wait.until(d -> {
                    String body = d.findElement(By.tagName("body")).getText();
                    return body.contains("전세율");
                });

                List<MarketRow> rows = scrapeRenderedTable(driver, region);
                System.out.println("  추출: " + rows.size() + "개");

                allRows.addAll(rows);
            }
        } finally {
            driver.quit();
        }

        String outName = "month_price_" +
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";

        try (BufferedWriter bw = Files.newBufferedWriter(
                Paths.get(outName), StandardCharsets.UTF_8)) {

            bw.write('\uFEFF');
            bw.write("region,area,people,수요,입주,매매,전세,전세가율");
            bw.newLine();

            for (MarketRow r : allRows) {
                bw.write(csv(r.region())); bw.write(',');
                bw.write(csv(r.area())); bw.write(',');
                bw.write(csv(fmt(r.people()))); bw.write(',');
                bw.write(csv(fmt(r.demand()))); bw.write(',');
                bw.write(csv(fmt(r.moveIn()))); bw.write(',');
                bw.write(csv(fmt(r.sale()))); bw.write(',');
                bw.write(csv(fmt(r.jeonse()))); bw.write(',');
                bw.write(csv(r.ratio() == null ? "" : r.ratio() + "%"));
                bw.newLine();
            }
        }

        System.out.println();
        System.out.println("완료: " + Paths.get(outName).toAbsolutePath());
        System.out.println("총 행 수: " + allRows.size());
    }

    private static List<MarketRow> scrapeRenderedTable(WebDriver driver, String region) {
        List<MarketRow> rows = new ArrayList<>();

        for (WebElement tr : driver.findElements(By.cssSelector("tr"))) {
            List<WebElement> cells = tr.findElements(By.cssSelector("th,td"));
            if (cells.size() < 11) continue;

            List<String> text = new ArrayList<>();
            for (WebElement c : cells) text.add(norm(c.getText()));

            int areaIdx = findAreaCell(text);
            if (areaIdx < 0 || areaIdx + 10 >= text.size()) continue;

            String areaCell = text.get(areaIdx);
            Integer complexCnt = extractComplexCount(areaCell);
            String areaName = stripComplexCount(areaCell);
            String area = complexCnt == null ? areaName : areaName + "(" + complexCnt + ")";

            Integer people = firstInteger(text.get(areaIdx + 3));
            Integer demand = firstInteger(text.get(areaIdx + 4));
            Integer moveIn = firstInteger(text.get(areaIdx + 5));
            Integer sale = firstInteger(text.get(areaIdx + 8));
            Integer jeonse = firstInteger(text.get(areaIdx + 9));
            Integer ratio = firstInteger(text.get(areaIdx + 10));

            if (people == null || demand == null || moveIn == null ||
                sale == null || jeonse == null || ratio == null) continue;

            rows.add(new MarketRow(region, area, people, demand, moveIn, sale, jeonse, ratio));
        }

        LinkedHashMap<String, MarketRow> dedup = new LinkedHashMap<>();
        for (MarketRow r : rows) dedup.putIfAbsent(r.region() + "|" + r.area(), r);
        return new ArrayList<>(dedup.values());
    }

    private static int findAreaCell(List<String> cells) {
        Pattern p = Pattern.compile(".*(?:시|군|구)(?:\\s+[가-힣]+구)?\\s*\\(\\s*\\d+\\s*\\).*");
        for (int i = 0; i < cells.size(); i++) {
            if (p.matcher(cells.get(i)).matches()) return i;
        }
        return -1;
    }

    private static Integer extractComplexCount(String s) {
        Matcher m = Pattern.compile("\\((\\d+)\\)").matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String stripComplexCount(String s) {
        return norm(s.replaceAll("\\(\\s*\\d+\\s*\\)", ""));
    }

    private static Integer firstInteger(String s) {
        Matcher m = Pattern.compile("-?[\\d,]+").matcher(s == null ? "" : s);
        if (!m.find()) return null;
        try {
            return Integer.valueOf(m.group().replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }

    private static String fmt(Integer n) {
        return n == null ? "" : String.format(Locale.US, "%,d", n);
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String norm(String s) {
        return s == null ? "" : s.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
    }
}
