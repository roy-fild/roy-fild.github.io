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
 * 부동산지인 month_price_YYYYMMDD.csv 생성기
 *
 * Java 17+
 *
 * 핵심:
 *  - gin01 현재 지역표: 단지수 / 매매 / 전세 / 전세가율
 *  - gin05 현재 지역표: 인구수
 *  - getGin0501 POST 응답의 "첫 번째 시계열 배열만": 당해 수요 / 입주
 *
 * 기존 AptginSupplyDemandCrawler의 문제:
 *  응답 전체의 yyyy 객체를 모두 읽고 같은 연도를 덮어써서,
 *  첫 번째 시계열의 올바른 apt_ent_cnt가 뒤쪽 배열 값으로 바뀔 수 있었음.
 *
 * 실행:
 *   javac -encoding UTF-8 AptginMonthPriceCrawler.java
 *   java inv.AptginMonthPriceCrawler
 *
 * package inv 이므로 일반적으로:
 *   javac -encoding UTF-8 -d . AptginMonthPriceCrawler.java
 *   java inv.AptginMonthPriceCrawler
 *
 * 출력:
 *   month_price_YYYYMMDD.csv
 */
public class AptginMonthPriceCrawler {

    private static final String BASE = "https://aptgin.com";
    private static final String GIN01_PAGE = BASE + "/home/gin01/gin0101/loc/";
    private static final String GIN05_PAGE = BASE + "/home/gin05/gin0501/loc/";
    private static final String GIN05_API  = BASE + "/home/gin05/getGin0501";

    private static final long DELAY_MS = 300L;

    private static final LinkedHashMap<String,String> PROVINCES = new LinkedHashMap<>();
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

    private static final CookieManager COOKIE_MANAGER =
            new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .cookieHandler(COOKIE_MANAGER)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private record Region(String sido, String name, String code, int lv, String dosiguYn) {}
    private record PriceInfo(Integer complexes, Integer sale, Integer jeonse, Integer ratio) {}
    private record DemandSupply(Integer demand, Integer supply) {}

    public static void main(String[] args) throws Exception {
        LocalDate today = LocalDate.now();
        int year = today.getYear();

        System.out.println("부동산지인 month_price 전체 최신 스크래핑 시작");
        System.out.println("기준 실행일: " + today);

        LinkedHashMap<String, Region> regions = discoverRegions();
        System.out.printf("발견 지역: %,d개%n", regions.size());

        // 현재 데이터 모음
        Map<String, PriceInfo> priceMap = new HashMap<>();
        Map<String, Integer> peopleMap = new HashMap<>();

        // 시도별 페이지를 한 번씩 읽어 가격/인구 표 수집
        for (Map.Entry<String,String> e : PROVINCES.entrySet()) {
            String sido = e.getKey();
            String code = e.getValue();

            try {
                String gin01 = get(GIN01_PAGE + code);
                parseGin01Rows(gin01, priceMap);
            } catch (Exception ex) {
                System.err.println("[GIN01 표 실패] " + sido + " : " + ex.getMessage());
            }

            try {
                String gin05 = get(GIN05_PAGE + code);
                parseGin05PeopleRows(gin05, peopleMap);
            } catch (Exception ex) {
                System.err.println("[GIN05 인구 실패] " + sido + " : " + ex.getMessage());
            }

            Thread.sleep(DELAY_MS);
        }

        String outName = "month_price_" +
                today.format(DateTimeFormatter.BASIC_ISO_DATE) + ".csv";

        int ok = 0;
        int partial = 0;

        try (BufferedWriter bw = Files.newBufferedWriter(
                Paths.get(outName), StandardCharsets.UTF_8)) {

            bw.write('\uFEFF');
            bw.write("region,area,people,수요,입주,매매,전세,전세가율");
            bw.newLine();

            for (Region r : regions.values()) {
                // 상위 시도 자체는 기존 month_price 형식에서 일반적으로 제외
                if (PROVINCES.containsValue(r.code())) continue;

                PriceInfo p = priceMap.get(r.code());
                Integer people = peopleMap.get(r.code());

                DemandSupply ds = null;
                try {
                    ds = fetchCurrentDemandSupply(r, year);
                } catch (Exception ex) {
                    System.err.println("[수요/입주 실패] " + r.sido() + " " + r.name()
                            + " (" + r.code() + ") : " + ex.getMessage());
                }

                Integer complexes = p == null ? null : p.complexes();
                Integer sale      = p == null ? null : p.sale();
                Integer jeonse    = p == null ? null : p.jeonse();
                Integer ratio     = p == null ? null : p.ratio();
                Integer demand    = ds == null ? null : ds.demand();
                Integer supply    = ds == null ? null : ds.supply();

                String area = r.name() + (complexes == null ? "" : "(" + complexes + ")");

                bw.write(csv(r.sido())); bw.write(',');
                bw.write(csv(area)); bw.write(',');
                bw.write(numComma(people)); bw.write(',');
                bw.write(numComma(demand)); bw.write(',');
                bw.write(numComma(supply)); bw.write(',');
                bw.write(numComma(sale)); bw.write(',');
                bw.write(numComma(jeonse)); bw.write(',');
                bw.write(ratio == null ? "" : ratio + "%");
                bw.newLine();

                boolean complete = p != null && people != null && ds != null;
                if (complete) ok++; else partial++;

                System.out.printf("%-6s %-18s people=%s demand=%s supply=%s sale=%s jeonse=%s ratio=%s%n",
                        r.sido(), r.name(),
                        val(people), val(demand), val(supply),
                        val(sale), val(jeonse), ratio == null ? "" : ratio + "%");

                bw.flush();
                Thread.sleep(DELAY_MS);
            }
        }

        System.out.println();
        System.out.println("완료: " + Paths.get(outName).toAbsolutePath());
        System.out.printf("완전 수집: %,d / 일부 누락: %,d%n", ok, partial);
    }

    private static LinkedHashMap<String, Region> discoverRegions() throws Exception {
        LinkedHashMap<String, Region> all = new LinkedHashMap<>();

        for (Map.Entry<String,String> e : PROVINCES.entrySet()) {
            String sido = e.getKey();
            String provinceCode = e.getValue();

            all.put(provinceCode, new Region(sido, sido, provinceCode, 1, "N"));

            String html = get(GIN01_PAGE + provinceCode);

            Pattern p = Pattern.compile(
                    "href\\s*=\\s*[\"'][^\"']*/home/gin01/gin0101/loc/(\\d{10})[\"'][^>]*>(.*?)</a>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

            Matcher m = p.matcher(html);
            while (m.find()) {
                String code = m.group(1);
                String name = cleanText(m.group(2));

                if (name.isBlank()) continue;
                if (PROVINCES.containsValue(code)) continue;
                if (!(name.endsWith("시") || name.endsWith("군") || name.endsWith("구"))) continue;

                int lv = name.contains(" ") ? 3 : 2;
                String dosiguYn = (name.endsWith("시") && !name.contains(" ")) ? "Y" : "N";

                all.putIfAbsent(code, new Region(sido, name, code, lv, dosiguYn));
            }

            Thread.sleep(DELAY_MS);
        }

        return all;
    }

    /**
     * gin01의 서버 렌더링 현재 표에서:
     * 지역코드 / 단지수 / 매매 / 전세 / 전세율을 뽑는다.
     *
     * row 텍스트 숫자 순서는 페이지 구조 변경에 취약하므로
     * 통화/퍼센트 표기를 기준으로 보수적으로 파싱한다.
     */
    private static void parseGin01Rows(String html, Map<String, PriceInfo> out) {
        for (String tr : extractRows(html)) {
            Matcher cm = Pattern.compile(
                    "/home/gin01/gin0101/loc/(\\d{10})",
                    Pattern.CASE_INSENSITIVE).matcher(tr);
            if (!cm.find()) continue;
            String code = cm.group(1);

            String text = cleanText(tr);

            // 금액 "... 만 원" 2개
            List<Integer> money = new ArrayList<>();
            Matcher mm = Pattern.compile("([\\d,]+)\\s*만\\s*원").matcher(text);
            while (mm.find()) money.add(parseInt(mm.group(1)));

            // 전세율 %
            Integer ratio = null;
            Matcher rm = Pattern.compile("([\\d.]+)\\s*%").matcher(text);
            if (rm.find()) ratio = (int)Math.round(Double.parseDouble(rm.group(1)));

            // HTML 구조상 마지막 숫자 2개가 단지수/세대수인 행이므로
            // 태그 제거 후 숫자 토큰에서 추정
            List<Integer> ints = allIntegerTokens(text);
            Integer complexes = null;
            if (ints.size() >= 2) {
                // 세대수는 마지막, 단지수는 그 앞
                complexes = ints.get(ints.size() - 2);
            }

            if (money.size() >= 2) {
                out.put(code, new PriceInfo(complexes, money.get(0), money.get(1), ratio));
            }
        }
    }

    /**
     * gin05 현재 지역별 수요/입주 표에서 지역별 인구수만 추출.
     * 첫 번째 숫자 큰 값(인구수)을 사용한다.
     */
    private static void parseGin05PeopleRows(String html, Map<String, Integer> out) {
        for (String tr : extractRows(html)) {
            Matcher cm = Pattern.compile(
                    "/home/gin05/gin0501/loc/(\\d{10})",
                    Pattern.CASE_INSENSITIVE).matcher(tr);
            if (!cm.find()) continue;
            String code = cm.group(1);

            String text = cleanText(tr);
            List<Integer> ints = allIntegerTokens(text);

            // 행에는 지역명 뒤 인구수, 수요, 입주... 순으로 숫자가 옴.
            // 인구수는 통상 가장 큰 첫 숫자.
            Integer people = null;
            for (Integer n : ints) {
                if (n >= 1000) {
                    people = n;
                    break;
                }
            }
            if (people != null) out.put(code, people);
        }
    }

    /**
     * 핵심 수정:
     * getGin0501 응답 전체를 훑지 않고 "첫 번째 최상위 배열"만 파싱한다.
     * 그 안의 yyyy == currentYear 객체에서 demand_cnt / apt_ent_cnt 사용.
     */
    private static DemandSupply fetchCurrentDemandSupply(Region r, int currentYear) throws Exception {
        String referer = GIN05_PAGE + r.code();
        get(referer); // 세션/쿠키

        YearMonth now = YearMonth.now();
        YearMonth prev = now.minusMonths(1);
        YearMonth nextYear = now.plusYears(1);

        Map<String,String> form = new LinkedHashMap<>();
        form.put("loc", r.code());
        form.put("lv", Integer.toString(r.lv()));
        form.put("aptType", "1");
        form.put("year", Integer.toString(currentYear));
        form.put("genDt02YM", prev.format(DateTimeFormatter.ofPattern("yyyyMM")));
        form.put("frDate", now.format(DateTimeFormatter.ofPattern("yyyyMM")));
        form.put("toDate", nextYear.format(DateTimeFormatter.ofPattern("yyyyMM")));
        form.put("baseDt", Integer.toString(currentYear));
        form.put("baseBf01Dt", Integer.toString(currentYear - 1));
        form.put("baseAf01Dt", Integer.toString(currentYear + 1));
        form.put("baseAf02Dt", Integer.toString(currentYear + 2));
        form.put("baseAf03Dt", Integer.toString(currentYear + 3));
        form.put("baseAf04Dt", Integer.toString(currentYear + 4));
        form.put("dosigu_yn", r.dosiguYn());

        HttpRequest req = HttpRequest.newBuilder(URI.create(GIN05_API))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", ua())
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Origin", BASE)
                .header("Referer", referer)
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build();

        HttpResponse<String> res = CLIENT.send(
                req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode());
        }

        String firstArray = extractFirstTopLevelInnerArray(res.body());

        Pattern objP = Pattern.compile("\\{[^{}]*\\}");
        Matcher om = objP.matcher(firstArray);

        while (om.find()) {
            String obj = om.group();
            Integer y = extractInt(obj, "yyyy");
            if (y == null || y != currentYear) continue;

            Integer demand = extractInt(obj, "demand_cnt");
            Integer supply = extractInt(obj, "apt_ent_cnt");

            if (demand != null && demand == 0) demand = null;
            if (supply != null && supply == 0) supply = null;

            return new DemandSupply(demand, supply);
        }

        return new DemandSupply(null, null);
    }

    /**
     * 응답 형태가 [[...], [...], ...] 일 때 첫 번째 내부 [...]만 잘라낸다.
     * 문자열 내부의 대괄호는 무시한다.
     */
    private static String extractFirstTopLevelInnerArray(String json) {
        int outer = json.indexOf('[');
        if (outer < 0) throw new IllegalArgumentException("JSON outer array 없음");

        boolean inString = false;
        boolean escaped = false;
        int start = -1;
        int depth = 0;

        for (int i = outer + 1; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '[') {
                if (start < 0) {
                    start = i;
                    depth = 1;
                } else {
                    depth++;
                }
            } else if (c == ']' && start >= 0) {
                depth--;
                if (depth == 0) {
                    return json.substring(start, i + 1);
                }
            }
        }

        throw new IllegalArgumentException("첫 번째 내부 배열 파싱 실패");
    }

    private static List<String> extractRows(String html) {
        List<String> rows = new ArrayList<>();
        Matcher m = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find()) rows.add(m.group(1));
        return rows;
    }

    private static Integer extractInt(String obj, String key) {
        Matcher m = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?(-?\\d+)\"?"
        ).matcher(obj);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static List<Integer> allIntegerTokens(String text) {
        List<Integer> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\d.])([\\d,]+)(?![\\d.])").matcher(text);
        while (m.find()) {
            try {
                out.add(parseInt(m.group(1)));
            } catch (Exception ignore) {}
        }
        return out;
    }

    private static int parseInt(String s) {
        return Integer.parseInt(s.replace(",", "").trim());
    }

    private static String cleanText(String html) {
        String s = html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", ua())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET()
                .build();

        HttpResponse<String> res = CLIENT.send(
                req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (res.statusCode() != 200) {
            throw new IOException("GET " + url + " -> HTTP " + res.statusCode());
        }
        return res.body();
    }

    private static String formEncode(Map<String,String> data) {
        StringJoiner j = new StringJoiner("&");
        data.forEach((k,v) -> j.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" +
                URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    private static String numComma(Integer n) {
        return n == null ? "" : String.format(Locale.US, "%,d", n);
    }

    private static String val(Integer n) {
        return n == null ? "" : n.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String ua() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/156.0.0.0 Safari/537.36";
    }
}
