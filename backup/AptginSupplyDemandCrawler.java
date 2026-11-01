package inv;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/**
 * Aptgin 전국 연도별 아파트 입주(공급) / 수요 CSV 수집기
 *
 * Java 17+
 *
 * 실행:
 *   javac AptginSupplyDemandCrawler.java
 *   java AptginSupplyDemandCrawler
 *
 * 출력:
 *   aptgin_supply_demand_YYYYMMDD.csv
 *
 * 주의:
 * - 부동산지인 웹 페이지와 내부 POST API 구조가 바뀌면 수정이 필요할 수 있습니다.
 * - 과도한 요청을 피하기 위해 기본 350ms 간격으로 요청합니다.
 */
public class AptginSupplyDemandCrawler {

    private static final String BASE = "https://aptgin.com";
    private static final String PAGE_PREFIX = BASE + "/home/gin05/gin0501/loc/";
    private static final String API_URL = BASE + "/home/gin05/getGin0501";

    private static final long REQUEST_DELAY_MS = 350L;

    // 2026-10 기준 부동산지인 상위 시도 URL 코드
    private static final LinkedHashMap<String, String> PROVINCES = new LinkedHashMap<>();
    static {
        PROVINCES.put("서울", "1100000000");
        PROVINCES.put("경기", "4100000000");
        PROVINCES.put("인천", "2800000000");
        PROVINCES.put("부산", "2600000000");
        PROVINCES.put("대구", "2700000000");
        PROVINCES.put("광주", "1200000000");
        PROVINCES.put("대전", "3000000000");
        PROVINCES.put("울산", "3100000000");
        PROVINCES.put("세종", "3600000000");
        PROVINCES.put("강원", "5100000000");
        PROVINCES.put("충북", "4300000000");
        PROVINCES.put("충남", "4400000000");
        PROVINCES.put("전북", "5200000000");
        PROVINCES.put("경북", "4700000000");
        PROVINCES.put("경남", "4800000000");
        PROVINCES.put("제주", "5000000000");
    }

    private static final Set<String> TOP_LEVEL_CODES = new HashSet<>(PROVINCES.values());

    private static final CookieManager COOKIE_MANAGER =
            new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .cookieHandler(COOKIE_MANAGER)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private record Region(String sido, String name, String code, int lv, String dosiguYn) {}
    private record YearData(int year, Integer supply, Integer demand) {}

    public static void main(String[] args) throws Exception {
        System.out.println("부동산지인 전국 수요/입주 데이터 수집 시작");

        List<Region> regions = discoverAllRegions();
        System.out.printf("수집 대상 지역: %,d개%n", regions.size());

        LocalDate today = LocalDate.now();
        String outName = "month_price_" +
                today.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";

        int ok = 0;
        int fail = 0;
        int dataRows = 0;

        try (BufferedWriter bw = Files.newBufferedWriter(
                Paths.get(outName), StandardCharsets.UTF_8)) {

            // Excel 한글 호환용 UTF-8 BOM
            bw.write('\uFEFF');
            bw.write("시도,지역,지역코드,연도,입주량,수요량");
            bw.newLine();

            for (int i = 0; i < regions.size(); i++) {
                Region r = regions.get(i);

                try {
                    List<YearData> yearly = fetchYearData(r);

                    if (yearly.isEmpty()) {
                        System.err.printf("[%d/%d] 데이터 없음: %s %s (%s)%n",
                                i + 1, regions.size(), r.sido(), r.name(), r.code());
                        fail++;
                    } else {
                        for (YearData d : yearly) {
                            bw.write(csv(r.sido()));
                            bw.write(',');
                            bw.write(csv(r.name()));
                            bw.write(',');
                            bw.write(r.code());
                            bw.write(',');
                            bw.write(Integer.toString(d.year()));
                            bw.write(',');
                            bw.write(d.supply() == null ? "" : d.supply().toString());
                            bw.write(',');
                            bw.write(d.demand() == null ? "" : d.demand().toString());
                            bw.newLine();
                            dataRows++;
                        }
                        ok++;
                        System.out.printf("[%d/%d] OK %-18s %s : %d년치%n",
                                i + 1, regions.size(),
                                r.sido() + " " + r.name(),
                                r.code(), yearly.size());
                    }
                } catch (Exception e) {
                    fail++;
                    System.err.printf("[%d/%d] FAIL %s %s (%s): %s%n",
                            i + 1, regions.size(), r.sido(), r.name(),
                            r.code(), e.getMessage());
                }

                bw.flush();
                Thread.sleep(REQUEST_DELAY_MS);
            }
        }

        System.out.println();
        System.out.println("완료");
        System.out.println("파일: " + Paths.get(outName).toAbsolutePath());
        System.out.printf("성공 지역: %,d / 실패 지역: %,d / CSV 데이터 행: %,d%n",
                ok, fail, dataRows);
    }

    /**
     * 각 시도 페이지의 href를 읽어 하위 행정구역 코드를 자동 발견.
     * 시도 자체도 결과에 포함한다.
     */
    private static List<Region> discoverAllRegions() throws Exception {
        LinkedHashMap<String, Region> all = new LinkedHashMap<>();

        for (Map.Entry<String, String> p : PROVINCES.entrySet()) {
            String sido = p.getKey();
            String provinceCode = p.getValue();

            // 시도 자체
            all.put(provinceCode, new Region(sido, sido, provinceCode, 1, "N"));

            String html = get(PAGE_PREFIX + provinceCode);

            // href="/home/gin05/gin0501/loc/1168000000">강남구</a>
            Pattern linkPattern = Pattern.compile(
                    "<a[^>]+href\\s*=\\s*[\"'][^\"']*/home/gin05/gin0501/loc/(\\d{10})[\"'][^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

            Matcher m = linkPattern.matcher(html);
            while (m.find()) {
                String code = m.group(1);

                // 다른 시도의 푸터 링크 제외
                if (TOP_LEVEL_CODES.contains(code)) {
                    continue;
                }

                String name = cleanHtmlText(m.group(2));
                if (name.isBlank()) {
                    continue;
                }

                // 현재 시도의 하위지역만 남김.
                // 한국 행정코드 계열은 보통 시도 코드의 앞 2자리가 동일.
                // aptgin의 광주 상위코드(12...)처럼 사이트 내부코드가 다른 경우에는
                // 페이지에 표시되는 지역명 패턴으로도 허용한다.
                if (!looksLikeLocalChild(sido, provinceCode, code, name)) {
                    continue;
                }

                int lv = name.contains(" ") ? 3 : 2;
                String dosiguYn = isCityAggregate(name) ? "Y" : "N";

                all.putIfAbsent(code, new Region(sido, name, code, lv, dosiguYn));
            }

            Thread.sleep(REQUEST_DELAY_MS);
        }

        return new ArrayList<>(all.values());
    }

    private static boolean looksLikeLocalChild(
            String sido, String provinceCode, String code, String name) {

        if (code.equals(provinceCode)) return false;

        // 일반적인 경우: 실제 행정코드 앞 2자리 동일
        if (code.substring(0, 2).equals(provinceCode.substring(0, 2))) {
            return true;
        }

        // 광주/전남처럼 사이트 상위 코드가 실제 법정코드 접두어와 다르게 노출될 수 있는 경우:
        // 페이지의 하위 지역명으로 판단. 전국 시도명 링크는 이미 위에서 제거됨.
        return name.endsWith("시") || name.endsWith("군") || name.endsWith("구");
    }

    private static boolean isCityAggregate(String name) {
        // "수원시" 같은 시 집계는 Y, "수원시 장안구" 같은 최하위 구는 N
        return name.endsWith("시") && !name.contains(" ");
    }

    private static List<YearData> fetchYearData(Region r) throws Exception {
        // 쿠키/세션 및 Referer 흐름을 맞추기 위해 먼저 페이지 GET
        String pageUrl = PAGE_PREFIX + r.code();
        get(pageUrl);

        LocalDate now = LocalDate.now();
        int year = now.getYear();

        YearMonth prevMonth = YearMonth.now().minusMonths(1);
        YearMonth thisMonth = YearMonth.now();
        YearMonth nextYearSameMonth = thisMonth.plusYears(1);

        Map<String, String> form = new LinkedHashMap<>();
        form.put("loc", r.code());
        form.put("lv", Integer.toString(r.lv()));
        form.put("aptType", "1");
        form.put("year", Integer.toString(year));
        form.put("genDt02YM", prevMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        form.put("frDate", thisMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        form.put("toDate", nextYearSameMonth.format(DateTimeFormatter.ofPattern("yyyyMM")));
        form.put("baseDt", Integer.toString(year));
        form.put("baseBf01Dt", Integer.toString(year - 1));
        form.put("baseAf01Dt", Integer.toString(year + 1));
        form.put("baseAf02Dt", Integer.toString(year + 2));
        form.put("baseAf03Dt", Integer.toString(year + 3));
        form.put("baseAf04Dt", Integer.toString(year + 4));
        form.put("dosigu_yn", r.dosiguYn());

        String body = formEncode(form);

        HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", browserUserAgent())
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", BASE)
                .header("Referer", pageUrl)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> res =
                CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode());
        }

        return parseYearData(res.body());
    }

    /**
     * 응답 전체에서 yyyy가 포함된 단순 객체를 찾고
     * apt_ent_cnt / demand_cnt를 꺼낸다.
     *
     * 0은 그래프상 값이 없는 연도로 취급하여 CSV 공란(null) 처리.
     */
    private static List<YearData> parseYearData(String json) {
        List<YearData> result = new ArrayList<>();

        Pattern objectPattern = Pattern.compile("\\{[^{}]*\"yyyy\"\\s*:\\s*\"?(\\d{4})\"?[^{}]*\\}");
        Matcher objectMatcher = objectPattern.matcher(json);

        while (objectMatcher.find()) {
            String obj = objectMatcher.group();

            Integer year = extractInt(obj, "yyyy");
            Integer apt = extractInt(obj, "apt_ent_cnt");
            Integer demand = extractInt(obj, "demand_cnt");

            if (year == null || apt == null) {
                continue;
            }

            // 사용자가 요청한 규칙: 없는 연도는 0이 아니라 공란
            Integer supplyOut = (apt == 0) ? null : apt;
            Integer demandOut = (demand == null || demand == 0) ? null : demand;

            result.add(new YearData(year, supplyOut, demandOut));
        }

        // 중복 방지 + 연도 정렬
        TreeMap<Integer, YearData> dedup = new TreeMap<>();
        for (YearData d : result) {
            dedup.put(d.year(), d);
        }

        return new ArrayList<>(dedup.values());
    }

    private static Integer extractInt(String obj, String key) {
        Pattern p = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?(-?\\d+)\"?");
        Matcher m = p.matcher(obj);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", browserUserAgent())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();

        HttpResponse<String> res =
                CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (res.statusCode() != 200) {
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        return res.body();
    }

    private static String formEncode(Map<String, String> data) {
        StringJoiner sj = new StringJoiner("&");
        data.forEach((k, v) -> sj.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" +
                URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return sj.toString();
    }

    private static String cleanHtmlText(String html) {
        String s = html.replaceAll("(?s)<[^>]*>", " ");
        s = s.replace("&nbsp;", " ")
             .replace("&amp;", "&")
             .replace("&lt;", "<")
             .replace("&gt;", ">")
             .replace("&quot;", "\"")
             .replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String browserUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/153.0.0.0 Safari/537.36";
    }
}
