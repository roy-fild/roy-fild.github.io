package com.sample;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Java 17+, org.json dependency from the existing project.
 */
public class ObserverTracking {
    private static final String OBSERVER_LIST_URL = "https://roy-fild.github.io/gain/observer/observer_list.csv";
    // 목록 주소를 비우면 아래 단지 ID를 직접 조회합니다.
    private static final String COMPLEX_ID = "";
    private static final String NAVER = "https://fin.land.naver.com/front-api/v1/complex";
    private static final String NAVER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String HIGH = "https://roy-fild.github.io/tracking/prev-high.json";
    private static final long DEFAULT_RATE_LIMIT_WAIT_SECONDS = 60;
    private static final long MAX_RATE_LIMIT_WAIT_SECONDS = 120;
    private static final int PAGE_SIZE = 30;
    private static final int MAX_PAGES = 200;
    private static final String[] HEADER = {
        "ID","구","동","단지","연식","세대","타입","유형","방","매매가","전세가","차액",
        "전세가율(%)","매","전","매/층","전/층","설명","key","전고점","변화율","수익율","수익금"
    };
    private static final Pattern FLOOR_NUMBER = Pattern.compile("^(-?\\d+)\\s*층?$");
    private static final Pattern AREA_NUMBER = Pattern.compile("^(\\d+)");
    private final String endpoint;
    private final long delayMillis;
    private final String naverOrigin;
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER))
            .build();
    private boolean naverSessionReady;
    private long lastRequestNanos;

    ObserverTracking(String endpoint, long delayMillis) {
        this.endpoint = endpoint;
        URI uri = URI.create(endpoint);
        this.naverOrigin = uri.getScheme() + "://" + uri.getRawAuthority();
        this.delayMillis = delayMillis;
    }

    public static void main(String[] args) throws Exception {
        String stamp = LocalDate.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.BASIC_ISO_DATE);
        Path output = Path.of("list_" + stamp + ".csv");
        if (!new ObserverTracking(NAVER, 1500).run(OBSERVER_LIST_URL, COMPLEX_ID, output, HIGH)) {
            System.exit(1);
        }
    }

    boolean run(String input, String directId, Path output, String highSource) throws Exception {
        Map<String, String> targets = readTargets(input, directId);
        System.out.printf("[INPUT] source=%s uniqueIds=%d%n", input.isBlank() ? "COMPLEX_ID" : input, targets.size());
        JSONObject peaks;
        try {
            peaks = highSource == null ? new JSONObject() : new JSONObject(readInput(highSource));
        } catch (IOException | RuntimeException | LookupFailure e) {
            System.out.printf("[WARN] stage=previous-high reason=%s%n", e.getMessage());
            peaks = new JSONObject();
        }
        int completed = 0, empty = 0, failures = 0, written = 0, position = 0;
        Path finalOutput = output.toAbsolutePath();
        Path temp = Files.createTempFile(finalOutput.getParent(), "observer-", ".tmp");
        Path published = null;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF');
                csv(writer, HEADER);
                for (var target : targets.entrySet()) {
                    String id = target.getKey(), region = target.getValue();
                    System.out.printf("[START %d/%d] region=%s id=%s%n", ++position, targets.size(), region, id);
                    try {
                        Scan scan = articles(id);
                        JSONObject meta = optional(id, "complex", endpoint + "?complexNumber=" + id);
                        JSONObject counts = optional(id, "counts", endpoint + "/article/count?complexNumber=" + id);
                        Map<String, Area> areas = areas(id, scan.listings());
                        int accepted = writeRows(writer, id, region, meta, counts, areas, scan, peaks);
                        written += areas.size();
                        if (accepted == 0) empty++;
                        else completed++;
                        System.out.printf("[%s] id=%s name=%s pages=%d groups=%d invalid=%d floorUnknown=%d types=%d pricedTypes=%d%n",
                                accepted > 0 ? "OK" : "NO_PRICE", id, field(meta,"name"), scan.pages(), scan.groups(),
                                scan.invalid(), scan.unknownFloor(), areas.size(), accepted);
                    } catch (LookupFailure e) {
                        failures++;
                        System.out.printf("[FAIL] id=%s %s%n", id, e.getMessage());
                        if (e.blocksRun()) {
                            System.out.printf("[STOP] remaining=%d%n", targets.size() - position);
                            break;
                        }
                    }
                }
            }
            if (failures == 0 && written > 0) {
                published = finalOutput;
            } else if (failures > 0 && completed > 0) {
                String filename = finalOutput.getFileName().toString().replaceFirst("(?i)\\.csv$", "");
                published = finalOutput.resolveSibling(filename + ".partial.csv");
            }
            if (published != null) Files.move(temp, published, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        System.out.printf("[SUMMARY] requested=%d attempted=%d ok=%d noPrice=%d failed=%d skipped=%d csvRows=%d output=%s%n",
                targets.size(), position, completed, empty, failures, targets.size() - position, written,
                published == null ? "없음 (기존 CSV 유지)" : published);
        return failures == 0;
    }

    Map<String, String> readTargets(String input, String directId) throws Exception {
        if (!input.isBlank()) return parseObservers(readInput(input.trim()));
        String id = directId.trim();
        if (!id.matches("[0-9]+")) {
            throw new IllegalArgumentException("목록 주소가 비어 있으면 COMPLEX_ID에 숫자 단지 ID를 입력하세요.");
        }
        return Map.of(id, "");
    }

    private String readInput(String source) throws Exception {
        if (!source.startsWith("http://") && !source.startsWith("https://")) return Files.readString(Path.of(source));
        return send("input", request(source).header("Cache-Control", "no-cache").GET().build());
    }

    static Map<String, String> parseObservers(String csv) {
        Map<String, String> targets = new LinkedHashMap<>();
        List<String> lines = csv.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) throw new IllegalArgumentException("observer_list.csv가 비어 있습니다.");
        List<String> header = splitCsv(lines.get(0).replaceFirst("^\uFEFF", ""));
        if (header.size() < 4 || !header.subList(0,4).equals(List.of("name","file","lv","id"))) {
            throw new IllegalArgumentException("CSV 헤더는 name,file,lv,id 순서여야 합니다.");
        }
        for (int row = 1; row < lines.size(); row++) {
            List<String> fields = splitCsv(lines.get(row));
            if (fields.size() < 3) throw new IllegalArgumentException("CSV 데이터 행 형식 오류: " + (row + 1));
            for (String field : fields.subList(3, fields.size())) {
                for (String value : field.split(",")) {
                    String id = value.trim();
                    if (id.isEmpty()) continue;
                    if (!id.matches("[0-9]+")) throw new IllegalArgumentException("잘못된 단지 ID: " + id + " / 행 " + (row + 1));
                    targets.putIfAbsent(id, fields.get(0));
                }
            }
        }
        if (targets.isEmpty()) throw new IllegalArgumentException("조회할 단지 ID가 없습니다.");
        return targets;
    }

    private static List<String> splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else field.append(c);
        }
        if (quoted) throw new IllegalArgumentException("CSV 따옴표가 닫히지 않았습니다.");
        fields.add(field.toString().trim());
        return fields;
    }

    private Scan articles(String id) throws InterruptedException, LookupFailure {
        startNaverSession();
        JSONArray cursor = new JSONArray();
        String seed = "";
        Set<String> cursors = new HashSet<>();
        Map<String, Offers> listings = new LinkedHashMap<>();
        int groups = 0, invalid = 0, unknownFloor = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            String stage = "articles page=" + page;
            JSONObject payload = new JSONObject().put("complexNumber",id)
                    .put("tradeTypes",new JSONArray()).put("pyeongTypes",new JSONArray()).put("dongNumbers",new JSONArray())
                    .put("userChannelType","PC").put("articleSortType","RANKING_DESC")
                    .put("seed",seed).put("lastInfo",cursor).put("size",PAGE_SIZE);
            HttpRequest req = naverRequest(endpoint + "/article/list",id)
                    .header("Sec-Fetch-Site","same-origin")
                    .header("Sec-Fetch-Mode","cors")
                    .header("Sec-Fetch-Dest","empty")
                    .header("Content-Type","application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            JSONObject result = result(stage,req);
            JSONArray list = result.optJSONArray("list");
            if (list == null) throw new LookupFailure(stage + " result.list 누락", 0);
            groups += list.length();
            for (int i = 0; i < list.length(); i++) {
                JSONObject group = list.optJSONObject(i);
                JSONObject info = group == null ? null : group.optJSONObject("representativeArticleInfo");
                if (info == null) { invalid++; continue; }
                String trade = field(info,"tradeType");
                if (!"A1".equals(trade) && !"B1".equals(trade)) continue;
                JSONObject space = info.optJSONObject("spaceInfo");
                JSONObject detail = info.optJSONObject("articleDetail");
                String type = field(space,"supplySpaceName");
                String exclusive = areaNumber(field(space,"exclusiveSpaceName"));
                BigDecimal raw = number(field(info.optJSONObject("priceInfo"),"A1".equals(trade) ? "dealPrice" : "warrantyPrice"));
                if (type.isEmpty() || raw == null || raw.signum() <= 0) { invalid++; continue; }
                String floorInfo = field(detail,"floorInfo");
                Floor level = floor(floorInfo);
                if (level == Floor.UNKNOWN) { unknownFloor++; continue; }
                Offer offer = new Offer(raw.movePointLeft(8), field(info,"dongName")+"("+floorInfo+")",
                        field(detail,"articleFeatureDescription"), level == Floor.LOW);
                String typeKey = type + "|" + exclusive;
                Offers offers = listings.computeIfAbsent(typeKey, key -> new Offers(type,exclusive));
                ("A1".equals(trade) ? offers.sale : offers.rent).add(offer);
            }
            seed = result.optString("seed",seed);
            Object next = result.opt("lastInfo");
            boolean more = result.optBoolean("hasNextPage",
                    result.optBoolean("hasMore", list.length() >= PAGE_SIZE));
            if (!more) return new Scan(listings,page,groups,invalid,unknownFloor);
            if (list.isEmpty() || next == null || JSONObject.NULL.equals(next)) {
                throw new LookupFailure(stage + " 다음 페이지 표시가 있지만 결과 또는 커서 없음", 0);
            }
            cursor = next instanceof JSONArray array ? array : new JSONArray().put(next);
            if (cursor.isEmpty() || !cursors.add(cursor.toString())) {
                throw new LookupFailure(stage + " 다음 페이지 커서가 비어 있거나 반복됨", 0);
            }
        }
        throw new LookupFailure("articles 페이지 제한 초과",0);
    }

    private Map<String, Area> areas(String id, Map<String, Offers> listings) throws InterruptedException, LookupFailure {
        Map<String, Area> areas = new LinkedHashMap<>();
        JSONObject types = optional(id,"pyeong-list",endpoint+"/building/pyeongList?complexNumber="+id);
        if (types != null) {
            for (String key : types.keySet().stream().sorted().toList()) {
                JSONObject area = optional(id,"pyeong="+key,endpoint+"/pyeong?complexNumber="+id
                        +"&pyeongTypeNumber="+URLEncoder.encode(key,StandardCharsets.UTF_8));
                String type = field(area,"name"), exclusive = areaNumber(field(area,"exclusiveArea"));
                if (type.isEmpty() || exclusive.isEmpty()) continue;
                String entry = switch (field(area,"entranceType")) { case "10" -> "계"; case "20" -> "복"; case "30" -> "복합"; default -> ""; };
                areas.putIfAbsent(type+"|"+exclusive,new Area(type,exclusive,key,
                        entry+"|"+field(area,"roomCount")+"|"+field(area,"bathRoomCount")));
            }
        }
        for (var entry : listings.entrySet()) {
            Offers offers = entry.getValue();
            if (offers.exclusive.isEmpty() && areas.values().stream().filter(area -> area.type().equals(offers.type)).count() == 1) continue;
            areas.putIfAbsent(entry.getKey(),new Area(offers.type,offers.exclusive,"",""));
        }
        return areas;
    }

    private static int writeRows(BufferedWriter writer, String id, String region, JSONObject meta,
            JSONObject counts, Map<String, Area> areas, Scan scan, JSONObject peaks) throws IOException {
        JSONObject address = meta == null ? null : meta.optJSONObject("address");
        JSONObject peakComplex = peaks.optJSONObject(id);
        JSONObject peakTypes = peakComplex == null ? null : peakComplex.optJSONObject("TP");
        String year = field(meta,"useApprovalDate");
        if (year.length() >= 4) year = year.substring(0,4);
        String district = field(address,"division");
        int priced = 0;
        for (var entry : areas.entrySet()) {
            Area area = entry.getValue();
            Offers match = scan.listings().get(entry.getKey());
            Offers untyped = scan.listings().get(area.type()+"|");
            if (!area.exclusive().isEmpty() && areas.values().stream().filter(a -> a.type().equals(area.type())).count() == 1
                    && untyped != null) {
                Offers merged = new Offers(area.type(),area.exclusive());
                merged.merge(match);
                merged.merge(untyped);
                match = merged;
            }
            Offer sale = match == null ? null : match.sale.best(), rent = match == null ? null : match.rent.best();
            BigDecimal m = sale == null ? null : sale.price(), j = rent == null ? null : rent.price();
            BigDecimal gap = m != null && j != null ? m.subtract(j) : null;
            BigDecimal peak = number(field(peakTypes,area.exclusive()));
            BigDecimal gain = peak != null && m != null ? peak.subtract(m) : null;
            if (m != null || j != null) {
                priced++;
                System.out.printf("[PRICE] id=%s type=%s/%s sale=%s%s rent=%s%s%n",id,area.exclusive(),area.type(),
                        text(m),sale != null && sale.low() ? "(LOW_FALLBACK)" : "",
                        text(j),rent != null && rent.low() ? "(LOW_FALLBACK)" : "");
            }
            csv(writer,id,district.isEmpty() ? region : district,field(address,"sector"),field(meta,"name"),year,
                    field(meta,"totalHouseholdNumber"),area.exclusive(),area.type(),area.rooms(),text(m),text(j),text(gap),
                    percent(j,m,""),field(counts,"dealCount"),field(counts,"leaseDepositCount"),
                    sale == null ? "" : sale.location(),rent == null ? "" : rent.location(),sale == null ? "" : sale.note(),
                    area.key(),text(peak),percent(gain == null ? null : gain.negate(),peak,"-"),percent(gain,gap,"-"),
                    gain == null ? "-" : text(gain.setScale(2,RoundingMode.HALF_UP)));
        }
        return priced;
    }

    private JSONObject optional(String id, String stage, String url) throws InterruptedException, LookupFailure {
        try {
            return result(stage,naverRequest(url,id).GET().build());
        } catch (LookupFailure e) {
            if (e.blocksRun()) throw e;
            System.out.printf("[WARN] id=%s %s%n",id,e.getMessage());
            return null;
        }
    }

    private JSONObject result(String stage, HttpRequest request) throws InterruptedException, LookupFailure {
        try {
            JSONObject response = new JSONObject(send(stage,request));
            if (!response.optBoolean("isSuccess",true)) {
                throw new LookupFailure(stage+" API "+response.optString("detailCode","error"),0);
            }
            JSONObject data = response.optJSONObject("result");
            if (data == null) throw new LookupFailure(stage+" result 누락",0);
            return data;
        } catch (IOException | RuntimeException e) {
            throw new LookupFailure(stage+" "+e.getClass().getSimpleName()+": "+e.getMessage(),0);
        }
    }

    private void startNaverSession() throws InterruptedException, LookupFailure {
        if (naverSessionReady) return;
        HttpRequest home = browserRequest(naverOrigin + "/")
                .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .GET().build();
        try {
            send("naver-home",home);
        } catch (IOException e) {
            throw new LookupFailure("naver-home "+e.getClass().getSimpleName()+": "+e.getMessage(),0);
        }
        naverSessionReady = true;
        System.out.println("[HOME] 네이버 세션 초기화 완료");
    }

    private HttpRequest.Builder naverRequest(String url, String id) {
        return browserRequest(url)
                .header("Accept","application/json, text/plain, */*")
                .header("Origin",naverOrigin)
                .header("Referer",naverOrigin+"/complexes/"+id+"?tab=article")
                .header("X-Requested-With","XMLHttpRequest");
    }

    private static HttpRequest.Builder browserRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("User-Agent",NAVER_USER_AGENT)
                .header("Accept-Language","ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
    }

    private String send(String stage, HttpRequest request) throws IOException, InterruptedException, LookupFailure {
        for (int attempt = 0; ; attempt++) {
            long elapsedMillis = (System.nanoTime()-lastRequestNanos)/1_000_000;
            if (elapsedMillis < delayMillis) Thread.sleep(delayMillis-elapsedMillis);
            lastRequestNanos = System.nanoTime();
            var response = client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) return response.body();

            String retryAfter = response.headers().firstValue("Retry-After").orElse("");
            if (status == 429 && attempt == 0) {
                long wait = retryWaitSeconds(retryAfter);
                if (wait <= MAX_RATE_LIMIT_WAIT_SECONDS) {
                    System.out.printf("[RETRY] stage=%s HTTP 429 wait=%ds%n",stage,wait);
                    Thread.sleep(wait * 1000);
                    continue;
                }
            }
            throw new LookupFailure(stage+" HTTP "+status+(retryAfter.isEmpty() ? "" : " retry-after="+retryAfter),status);
        }
    }

    static long retryWaitSeconds(String retryAfter) {
        String value = retryAfter.trim();
        if (value.matches("[0-9]+")) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }
        try {
            Duration remaining = Duration.between(Instant.now(),
                    ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return remaining.isNegative() ? 0 : remaining.plusMillis(999).toSeconds();
        } catch (DateTimeParseException e) {
            return DEFAULT_RATE_LIMIT_WAIT_SECONDS;
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25))
                .header("User-Agent","ObserverTracking/1.0").header("Accept","application/json, text/csv, text/plain");
    }

    private static Floor floor(String value) {
        String first = value.split("/",2)[0].trim();
        if (first.startsWith("저")) return Floor.LOW;
        if (first.startsWith("중") || first.startsWith("고")) return Floor.PREFERRED;
        Matcher matcher = FLOOR_NUMBER.matcher(first);
        if (!matcher.matches()) return Floor.UNKNOWN;
        int number = Integer.parseInt(matcher.group(1));
        if (number >= 4) return Floor.PREFERRED;
        return number >= 1 ? Floor.LOW : Floor.UNKNOWN;
    }

    private static String areaNumber(String value) {
        Matcher match = AREA_NUMBER.matcher(value);
        return match.find() ? match.group(1) : "";
    }
    private static String field(JSONObject value, String key) { return value == null ? "" : value.optString(key,"").trim(); }
    private static BigDecimal number(String value) {
        try { return new BigDecimal(value.replace(",","")); }
        catch (NumberFormatException e) { return null; }
    }
    private static String text(BigDecimal value) { return value == null ? "" : value.stripTrailingZeros().toPlainString(); }
    private static String percent(BigDecimal numerator, BigDecimal denominator, String empty) {
        if (numerator == null || denominator == null || denominator.signum() == 0) return empty;
        return numerator.movePointRight(2).divide(denominator,1,RoundingMode.HALF_UP).toPlainString()+"%";
    }
    private static void csv(BufferedWriter writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            String value = values[i];
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                writer.write('"');
                writer.write(value.replace("\"","\"\""));
                writer.write('"');
            } else writer.write(value);
        }
        writer.write("\r\n");
    }

    private enum Floor { PREFERRED, LOW, UNKNOWN }
    private record Offer(BigDecimal price, String location, String note, boolean low) {}
    private record Area(String type, String exclusive, String key, String rooms) {}
    private record Scan(Map<String, Offers> listings, int pages, int groups, int invalid, int unknownFloor) {}

    private static class Best {
        private Offer preferred, low;
        void add(Offer offer) {
            if (offer == null) return;
            if (offer.low()) {
                if (low == null || offer.price().compareTo(low.price()) < 0) low = offer;
            } else if (preferred == null || offer.price().compareTo(preferred.price()) < 0) preferred = offer;
        }
        Offer best() { return preferred != null ? preferred : low; }
    }

    private static class Offers {
        final String type, exclusive;
        final Best sale = new Best(), rent = new Best();
        Offers(String type, String exclusive) { this.type = type; this.exclusive = exclusive; }
        void merge(Offers other) {
            if (other == null) return;
            sale.add(other.sale.best());
            rent.add(other.rent.best());
        }
    }

    private static class LookupFailure extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;
        LookupFailure(String message, int status) { super(message); this.status = status; }
        boolean blocksRun() { return status == 401 || status == 403 || status == 429; }
    }
}
