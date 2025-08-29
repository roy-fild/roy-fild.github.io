package inv;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.IOUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/****
 * 현재 브라우저 문제로 이게 호출 가능 소스
 */
//@Slf4j
public class NaverLikeBrowser {

	private static final Logger log = LoggerFactory.getLogger(NaverLikeBrowserTEST.class);

	static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

	// 1) 쿠키 매니저 + 리다이렉트 허용 클라이언트 (전역 1개)
	static final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
			.followRedirects(HttpClient.Redirect.NORMAL).cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.connectTimeout(Duration.ofSeconds(15)).build();

	static HttpRequest.Builder base(String url) {
	    return HttpRequest.newBuilder(URI.create(url))
	        .timeout(Duration.ofSeconds(30))
	        .header("User-Agent", UA)
	        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	        .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
	}


	// 2) 웜업: 같은 도메인에서 아무 페이지 하나 먼저 열어 쿠키 확보
	static void warmup() throws Exception {
		String warm = "https://fin.land.naver.com/";
		HttpRequest req = base(warm).GET().build();
		HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
		// 200/302여도 쿠키만 잘 받으면 OK
//		System.out.println("warmup status=" + res.statusCode());
	}

	// 재사용 가능한 HttpClient
	private static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

	public static void main(String[] args) throws Exception {
		

//		String fileUrl = "https://roy-fild.github.io/file/suji-gu.xlsx"; // 수지구
//		String fileUrl = "https://roy-fild.github.io/file/seong-buk-gu.xlsx"; // 성북구
//		String fileUrl = "https://roy-fild.github.io/file/suwon-si-yeongtong-gu.xlsx"; // 영통구
//		String fileUrl = "https://roy-fild.github.io/file/case-test.xlsx"; // TEST용
		
		String prefix = "https://roy-fild.github.io/file/";
		
		String[] fileUrls = {
				// 정찰기
//				"tracking-list",		// 수도권
//				"tracking-jibang",		// 지방
				
				// 서울시
//				"songpa-gu",			// 송파구
				"seongdong-gu",			// 성동구
//				"yeongdeungpo-gu",		// 영등포구
//				"yangcheon-gu",			// 양천구
//				"jongno-jung-gu",		// 종로/중구
//				"dongjak-gu",			// 동작구		
//				"seong-buk-gu",			// 성북구
//				"dongdaemun-gu",		// 동대분구
//				"seodaemun-gu",			// 서대문구
//				"gwanak-gu",			// 관악구
//				"eunpyeong-gu",			// 은평구
//				"jungnang-gu",			// 중랑구
//				
//				// 수도권
//				"suji-gu",				// 수지구
//				"bundang-gu",			// 성남시_분당구
//				"anyang-si-dongan-gu",	// 안양시_동안구
//				"suwon-si-yeongtong-gu",// 수원시_영통구
//				"dongtan",				// 화성시_동탄	
//				"sujeong-jungwon-gu",	// 성남시_수정/증원구
//				
//				// 광역시
//				"daejeon-seo-gu",		// 대전_서구
//				"daejeon-yuseong-gu", 	// 대전_유성구
//				"gwangju-buk-gu",		// 광주_북구
//				
//				// 중소도시
//				"cheonan",				// 천안시
//				"cheongju",				// 청주
//				"jeonju",				// 전주
//				"pohang-si-buk-gu",		// 포항시_북구
		};

		// ===== 멀티스레드: 파일 단위 병렬 처리 =====
//        final int cores = Math.max(2, Runtime.getRuntime().availableProcessors());
//        // 외부 API 부하/차단을 고려해 너무 크게 잡지 않도록: 코어 수 또는 6 중 작은 값
//        final int poolSize = Math.min(cores, 6);
//        ExecutorService es = Executors.newFixedThreadPool(poolSize);
//
//        List<Future<Void>> futures = new ArrayList<>();
//        long t0 = System.nanoTime();
//
//        for (String name : fileUrls) {
//            final String fileUrl = String.format("%s%s.xlsx", prefix, name);
//            Callable<Void> task = () -> {
//                try {
//                    readExcelFileFromUrl(fileUrl); // Excel 읽고, 네이버 조회하고, 엑셀로 내보내기
//                } catch (Throwable th) {
//                    // 개별 작업 실패해도 다른 작업은 계속되도록
//                    log.error("작업 실패: {}", fileUrl, th);
//                }
//                return null;
//            };
//            futures.add(es.submit(task));
//        }
//
//        // 모든 작업 완료 대기
//        for (Future<Void> f : futures) {
//            try {
//                f.get();
//            } catch (InterruptedException ie) {
//                Thread.currentThread().interrupt();
//                log.error("대기 중 인터럽트", ie);
//            } catch (ExecutionException ee) {
//                log.error("작업 예외", ee.getCause());
//            }
//        }
//
//        es.shutdown();
//        es.awaitTermination(5, TimeUnit.MINUTES);
//
//        long t1 = System.nanoTime();
//        log.info("모든 파일 처리 완료. 총 소요: {} ms (poolSize={})", (t1 - t0) / 1_000_000, poolSize);
        
        
        // AS-IS 순차
		for(String fileUrl : fileUrls) {	
			fileUrl = String.format("%s%s.xlsx", prefix, fileUrl);
			readExcelFileFromUrl(fileUrl); // Excel 읽어 오기
		}
    }


	// 1.github 에 있는 Excel 파일 조회
	public static void readExcelFileFromUrl(String fileUrl) {
		try {
			// 0) 정말 큰 파일 대비(필요시 상향: 200MB 예시)
			IOUtils.setByteArrayMaxOverride(200 * 1024 * 1024);

			// 1) 다운로드 (바이너리)
			HttpRequest req = HttpRequest.newBuilder(URI.create(fileUrl)).timeout(Duration.ofSeconds(60))
					.header("User-Agent", UA)
					.header("Accept",
							"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/octet-stream;q=0.9,*/*;q=0.5")
					.GET().build();

			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			int status = res.statusCode();
			String ctype = res.headers().firstValue("content-type").orElse("-");
			System.out.println("GET " + status + " content-type=" + ctype);

			if (status != 200) {
				Files.writeString(Path.of("xlsx_download_error.html"),
						"HTTP " + status + "\n\n" + new String(res.body()));
				throw new IllegalStateException("엑셀 다운로드 실패: HTTP " + status + " (xlsx_download_error.html 저장됨)");
			}

			byte[] body = res.body();

			// 2) 매직 바이트 확인 (XLSX=ZIP: 'P''K''\003''\004')
			boolean isZip = body.length >= 4 && body[0] == 0x50 && body[1] == 0x4B && body[2] == 0x03
					&& body[3] == 0x04;

			if (!isZip) {
				// HTML/텍스트 가능성 – 저장해서 내용 확인
				Path dump = Path.of("downloaded_non_xlsx.bin");
				Files.write(dump, body);
				String head = new String(body, 0, Math.min(body.length, 800));
				System.out.println("NOT XLSX. preview:\n" + head);
				throw new IllegalStateException("받은 파일이 XLSX가 아닙니다. saved: " + dump.toAbsolutePath());
			}

			// 3) Apache POI 파싱
			List<String> aptIds = new ArrayList<>();
			try (InputStream is = new ByteArrayInputStream(body); Workbook wb = new XSSFWorkbook(is)) {

				DataFormatter fmt = new DataFormatter();

				for (int s = 0; s < wb.getNumberOfSheets(); s++) {
					Sheet sheet = wb.getSheetAt(s);
					if (sheet == null)
						continue;

					for (Row row : sheet) {
						if (row == null)
							continue;

						Cell idCell = row.getCell(0, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
						String idStr = (idCell == null) ? "" : fmt.formatCellValue(idCell).trim();

						if (idStr.isEmpty() || idStr.equalsIgnoreCase("id"))
							continue;
//						System.out.println(idStr);
						aptIds.add(idStr);
					}
				}
			}

			// 4) 다음 단계로 전달
			// requestNaver(aptIds);

			JSONArray baseArr = new JSONArray();

			// 기본정보 조회
			for (String id : aptIds) {
				loadBaseData(id, baseArr);
				loadPyeongList(id, baseArr);
				getPriceData(id, baseArr, 0);
			}

//			JSONObject obj = pickOneById(baseArr, "100473");
//			log.debug("{}", obj);

			log.debug("{}", baseArr);
			
			String fileName = extractBaseName(fileUrl);

			Path out = Path.of(String.format("%s_%s.xlsx", fileName,getNowDate()));
			export(baseArr, out);
			System.out.println("엑셀 생성 완료: " + out.toAbsolutePath());

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// 2.아파트 기본 정보 조회
	public static void loadBaseData(String id, JSONArray baseArr) {
		try {
			warmup();

			// 3) 타깃 페이지: 반드시 Referer를 같은 도메인으로
			String url = "https://fin.land.naver.com/complexes/" + id + "?tab=article&tradeType=A1&pyeongTypeNumber=1";
			// + "&transactionPyeongTypeNumber=2&transactionTradeType=A1";

			HttpRequest req = base(url).header("Referer", "https://fin.land.naver.com/complexes/" + id + "?tab=article")
					.GET().build();

			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
//				System.out.println("status=" + res.statusCode());
			if (res.statusCode() != 200) {
				// 원인 파악용으로 응답 일부 출력
				System.out.println(res.body().substring(0, Math.min(800, res.body().length())));
			} else {
				// Jsoup 등으로 파싱
				Document doc = Jsoup.parse(res.body());

				// 1) 주소
				// jQuery: $('.HeaderBrandDepth-module_sub-name__t-5rA').text()
				String addr = selText(doc, ".HeaderBrandDepth-module_sub-name__t-5rA");
				String gu = "", dong = "";
				if (!addr.isEmpty()) {
					String[] addrSplit = addr.split("\\s+");
					if (addrSplit.length > 2) {
						gu = addrSubstr(addrSplit[1]);
						dong = addrSubstr(addrSplit[2]);
					} else {
						gu = addrSplit.length > 0 ? addrSubstr(addrSplit[0]) : "";
						dong = addrSplit.length > 1 ? addrSubstr(addrSplit[1]) : "";
					}
				}

				// 2) 아파트명
				// jQuery: $('.ComplexSummary_name__z0aZ7').text()
				String aptNm = selText(doc, ".ComplexSummary_name__z0aZ7");
				if (aptNm.isEmpty()) {
					// 클래스가 종종 바뀌니 대비(다른 빌드 변형 클래스)
					aptNm = selText(doc, ".ComplexSummary_name__vX3IN, .ComplexSummary_name__z0aZ7");
				}
				aptNm = clearAptNm(aptNm);

				// 3) 연식/세대수
				// jQuery:
				// $('.ComplexSummary_information__R5OGG').find('ul').eq(0).find('li').eq(2)
				// $('.ComplexSummary_information__R5OGG').find('ul').eq(0).find('li').eq(1)
				String aptYearInfo = selText(doc, ".ComplexSummary_information__R5OGG ul:eq(0) li:eq(2)");
				String aptSedaeRaw = selText(doc, ".ComplexSummary_information__R5OGG ul:eq(0) li:eq(1)");
				String aptYear = cvrtAptYear(aptYearInfo);
				String aptSedae = cvrtAptSaedae(aptSedaeRaw);

				// 4) 매물/전세 개수
				Elements counts = doc.select(".ComplexSummary_area-list-button__3Eglr .ComplexSummary_count__GFHb9");

				String mCnt = counts.size() > 0 ? counts.get(0).text() : "0"; // 매매
				String jCnt = counts.size() > 1 ? counts.get(1).text() : "0"; // 전세
				// 월세/단기가 필요하면 2, 3 인덱스

				JSONObject baseObj = new JSONObject();

				baseObj.put("id", id);
				baseObj.put("aptNm", aptNm);
				baseObj.put("gu", gu);
				baseObj.put("dong", dong);
				baseObj.put("year", aptYear);
				baseObj.put("sd", aptSedae);
				baseObj.put("mCnt", mCnt);
				baseObj.put("jCnt", jCnt);

				baseArr.put(baseObj);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// 3.방 리스트 정보
	public static void loadPyeongList(String id, JSONArray baseArr) {
		try {
			warmup();

			// 3) 타깃 페이지: 반드시 Referer를 같은 도메인으로
			String url = "https://fin.land.naver.com/front-api/v1/complex/building/pyeongList?complexNumber=" + id;

			HttpRequest req = base(url).header("Referer", "https://fin.land.naver.com/complexes/" + id + "?tab=article")
					.GET().build();

			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
//			System.out.println("status=" + res.statusCode());
			if (res.statusCode() != 200) {
				// 원인 파악용으로 응답 일부 출력
				System.out.println(res.body().substring(0, Math.min(800, res.body().length())));
			} else {
				String body = res.body(); // <-- 실제 JSON 문자열
				// System.out.println(body); // 형태 확인하고 싶으면 출력

				JSONObject root = new JSONObject(body);
				JSONObject result = root.getJSONObject("result"); // JS에서 Object.keys(res.result) 쓰던 그 부분

				JSONArray subArr = new JSONArray();

				// 평 리스트 가져오기
				for (String key : result.keySet()) { // "1","2","3", ...

//			    	System.out.println(key);
					loadPyeongDetailinfo(id, key, subArr);
				}

				JSONObject obj = pickOneById(baseArr, id);
				if (obj != null) {
					obj.put("subInfo", subArr); // ✅ 이걸로 끝
				} else {
					// 혹시 기본정보가 없을 때만 새로 추가
					JSONObject newObj = new JSONObject().put("id", id).put("subInfo", subArr);
					baseArr.put(newObj);
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// 4.방 상세정보
	public static void loadPyeongDetailinfo(String id, String key, JSONArray subArr) {

		JSONObject pObj = new JSONObject();

		try {
			warmup();

			// 3) 타깃 페이지: 반드시 Referer를 같은 도메인으로
			String url = "https://fin.land.naver.com/front-api/v1/complex/pyeong?complexNumber=" + id
					+ "&pyeongTypeNumber=" + key;

			HttpRequest req = base(url).header("Referer", "https://fin.land.naver.com/complexes/" + id + "?tab=article")
					.GET().build();

			HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
//			System.out.println("status=" + res.statusCode());
			if (res.statusCode() != 200) {
				// 원인 파악용으로 응답 일부 출력
				System.out.println(res.body().substring(0, Math.min(800, res.body().length())));
			} else {
				String body = res.body(); // <-- 실제 JSON 문자열
//			    System.out.println(body);               // 형태 확인하고 싶으면 출력

				JSONObject root = new JSONObject(body);
				JSONObject result = root.getJSONObject("result"); // JS에서 Object.keys(res.result) 쓰던 그 부분

				// 방상세정보
				String type = result.getString("name");
				String roomCnt = result.get("roomCount").toString();
				String batchRoom = result.get("bathRoomCount").toString();
				String entranceType = getEntranceName(result.get("entranceType").toString()); // 10:계 20:복 30:복합
				
				double exclusiveArea = result.getDouble("exclusiveArea");
				int intExclusiveArea = (int) exclusiveArea;

//			    System.out.println(entranceType +"|"+ roomCnt+"|"+batchRoom);

				String info = String.format("%s|%s|%s", entranceType, roomCnt, batchRoom);

				pObj.put("key", key);
				pObj.put("type", type);
				pObj.put("space", Integer.toString(intExclusiveArea));
				pObj.put("info", info);

				subArr.put(pObj);

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// 5 매/전가 조회
	public static void getPriceData(String id, JSONArray baseArr, int ignoredStartPage) {
	    JSONObject mObj = new JSONObject(); // 매매
	    JSONObject jObj = new JSONObject(); // 전세

	    try {
	        // 세션 워밍업 (쿠키/세션 확보)
	        warmup();

	        final String endpoint = "https://fin.land.naver.com/front-api/v1/complex/article/list";
	        final int size = 30;                    // 한 번에 받아올 개수
	        final String sortType = "RANKING_DESC"; // 혹은 "PRICE_ASC" 등 화면과 일치시키세요.
	        JSONArray lastInfo = new JSONArray();   // 서버 응답에서 반환되는 lastInfo를 이어서 사용
	        boolean hasNext = true;                 // 다음 페이지 존재 여부(안 오면 size로 추정)

	        while (hasNext) {
	            // 1) 페이로드 구성 (브라우저 페이로드 스키마와 일치)
	            JSONObject payload = new JSONObject()
	                    .put("complexNumber", id)
	                    .put("tradeTypes", new JSONArray())      // 필요하면 ["A1"] 등으로
	                    .put("pyeongTypes", new JSONArray())     // 필요하면 ["84"] 등으로
	                    .put("dongNumbers", new JSONArray())     // 필요하면 ["101"] 등으로
	                    .put("userChannelType", "PC")
	                    .put("articleSortType", sortType)
	                    .put("seed", "")
	                    .put("lastInfo", lastInfo)               // 첫 호출은 빈 배열, 이후 응답값 주입
	                    .put("size", size);

	            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
	                    .timeout(Duration.ofSeconds(30))
	                    .header("User-Agent", UA)
	                    .header("Accept", "application/json, text/plain, */*")
	                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
	                    .header("Origin", "https://fin.land.naver.com")
	                    .header("Referer", "https://fin.land.naver.com/complexes/" + id + "?tab=article")
	                    .header("X-Requested-With", "XMLHttpRequest")
	                    .header("Sec-Fetch-Site", "same-origin")
	                    .header("Sec-Fetch-Mode", "cors")
	                    .header("Sec-Fetch-Dest", "empty")
	                    .header("Content-Type", "application/json;charset=UTF-8")
	                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
	                    .build();

	            HttpResponse<String> res = sendWithRetry429(req, 4); // 429/5xx 재시도
	            if (res.statusCode() != 200) {
	                System.out.println("[getPriceData:POST] HTTP " + res.statusCode()
	                        + " body preview: " + res.body().substring(0, Math.min(800, res.body().length())));
	                break;
	            }

	            JSONObject root = new JSONObject(res.body());
	            JSONObject result = root.getJSONObject("result");
	            JSONArray list = result.optJSONArray("list");

	            if (list == null || list.length() == 0) {
	                break;
	            }

	            for (int i = 0; i < list.length(); i++) {
	                JSONObject item = list.getJSONObject(i);
	                JSONObject info = item.getJSONObject("representativeArticleInfo");

	                String dongName = info.optString("dongName", "");
	                JSONObject detail = info.optJSONObject("articleDetail");
	                JSONObject space = info.optJSONObject("spaceInfo");
	                JSONObject price = info.optJSONObject("priceInfo");

	                String desc = (detail != null) ? detail.optString("articleFeatureDescription", "") : "";
	                String tradeType = info.optString("tradeType", ""); // A1(매매) / B1(전세)
	                String supplyType = (space != null) ? space.optString("supplySpaceName", "") : "";
	                String spaceType = (space != null) ? space.optString("exclusiveSpaceName", "") : "";
	                String floorInfo = (detail != null) ? detail.optString("floorInfo", "") : "";

	                String dealPrice = formatToEok((price != null) ? price.opt("dealPrice") : null);
	                String rentPrice = formatToEok((price != null) ? price.opt("warrantyPrice") : null);

	                if (chkFloor(floorInfo)) {
	                    String floor = dongName + "(" + floorInfo + ")";
	                    if ("A1".equals(tradeType)) {
	                        createObj(mObj, dealPrice, supplyType, spaceType, floor, desc);
	                    } else if ("B1".equals(tradeType)) {
	                        createObj(jObj, rentPrice, supplyType, spaceType, floor, desc);
	                    }
	                }
	            }

	            // 2) 다음 페이징 준비
	            // 서버가 hasNextPage를 주면 그대로 사용, 아니면 list 길이로 추정
	            hasNext = result.optBoolean("hasNextPage",
	                        list.length() >= size || result.optBoolean("hasMore", false));

	            // 서버가 next를 위해 lastInfo를 내려주면 이어붙여서 다음 요청에 사용
	            Object li = result.opt("lastInfo");
	            if (li instanceof JSONArray) {
	                lastInfo = (JSONArray) li;
	            } else if (li != null) {
	                // 혹시 객체/문자열로 내려오면 배열로 감싸서 사용
	                lastInfo = new JSONArray().put(li);
	            } else {
	                // 내려오지 않으면 관성적으로 종료 조건을 size로만 판단
	                if (list.length() < size) hasNext = false;
	            }

	            // 레이트 리밋 완화 간격
	            Thread.sleep(1500);
	        }

	        JSONObject subObj = pickOneById(baseArr, id);
	        if (subObj == null) {
	            subObj = new JSONObject().put("id", id);
	            baseArr.put(subObj);
	        }
	        subObj.put("dealInfo", mObj);
	        subObj.put("rentInfo", jObj);

	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	}

	/** 429/5xx 재시도 (Retry-After 존중 + 지수 백오프 + 지터) */
	private static HttpResponse<String> sendWithRetry429(HttpRequest req, int maxRetry) throws Exception {
	    long base = 1200L;
	    for (int attempt = 0; attempt <= maxRetry; attempt++) {
	        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
	        int code = res.statusCode();
	        if (code == 200) return res;

	        if (code == 429 || (code >= 500 && code < 600)) {
	            if (attempt == maxRetry) return res;
	            Optional<String> ra = res.headers().firstValue("Retry-After");
	            long sleepMs;
	            if (ra.isPresent()) {
	                try {
	                    sleepMs = Long.parseLong(ra.get().trim()) * 1000L;
	                } catch (NumberFormatException nfe) {
	                    sleepMs = base * (1L << attempt);
	                }
	            } else {
	                sleepMs = base * (1L << attempt) + (long)(Math.random() * 600 + 200);
	            }
	            System.out.println("Retry after " + sleepMs + " ms (status " + code + ", attempt " + (attempt+1) + ")");
	            Thread.sleep(Math.min(sleepMs, 10_000L));
	            continue;
	        }
	        return res; // 그 외 코드는 그대로 반환
	    }
	    throw new IllegalStateException("retry exhausted");
	}

	// 가격 비교 후 삽입
	public static void createObj(JSONObject obj, String priceInfo, String supplyType, String spaceType,
			String floorInfo, String desc) {
		spaceType = spaceType.trim();

//		log.debug("{}", obj);

		// 타입으로 이미 만들어진 오브젝트 여부 확인
		if (!obj.has(spaceType)) {
			// 최초 값
			setNewInfo(obj, priceInfo, supplyType, spaceType, floorInfo, desc);
		} else {

			JSONObject subObj = obj.getJSONObject(spaceType);

			String bfPrice = subObj.getString("priceInfo");
			String afPrice = priceInfo;

			if (Float.parseFloat(bfPrice) > Float.parseFloat(afPrice)) {
				setNewInfo(obj, priceInfo, supplyType, spaceType, floorInfo, desc);
			}
		}
	}

	public static void setNewInfo(JSONObject obj, String priceInfo, String supplyType, String spaceType,
			String floorInfo, String desc) {
		JSONObject p = new JSONObject();

		p.put("priceInfo", priceInfo);
		p.put("spaceType", spaceType);
		p.put("supplyType", supplyType);
		p.put("floorInfo", floorInfo);
		p.put("desc", desc);

		obj.put(spaceType, p);

	}

	public static void export(JSONArray jArr, Path outXlsx) throws Exception {

		try (Workbook wb = new XSSFWorkbook()) {
			Sheet sheet = wb.createSheet("data");

			// 헤더/서식
			Font bold = wb.createFont();
			bold.setBold(true);
			CellStyle headStyle = wb.createCellStyle();
			headStyle.setFont(bold);

			DataFormat fmt = wb.createDataFormat();

			CellStyle numStyle = wb.createCellStyle();
			numStyle.setDataFormat(fmt.getFormat("0.0########"));

			CellStyle pctStyle = wb.createCellStyle();
			pctStyle.setDataFormat(fmt.getFormat("0.0%"));

			// ── 컬럼 인덱스: 차액(10) → 전세가율(11) ─────────────────────
			final int COL_ID = 0;
			final int COL_GU = 1;
			final int COL_DONG = 2;
			final int COL_APTNM = 3;
			final int COL_YEAR = 4;
			final int COL_SD = 5;
			final int COL_SPACE = 6;
			final int COL_TYPE = 7;
			final int COL_ROOM = 8; // subinfo.info
			final int COL_DEAL = 9; // 매매가
			final int COL_RENT = 10; // 전세가
			final int COL_DIFF = 11; // 차액 
			final int COL_RATE = 12; // 전세가율(%) 
			final int COL_M = 13; // 매
			final int COL_J = 14; // 전
			final int COL_M_FR = 15; // 매/층
			final int COL_J_FR = 16; // 매/층
			final int COL_DESC = 17; // 설명
			final int COL_KEY = 18; // 방(KEY)

			// 헤더: "차액"이 "전세가율(%)"보다 먼저
			String[] headers = { "ID", "구", "동", "단지", "연식", "세대", "타입","유형", "방", "매매가", "전세가", "차액", "전세가율(%)", "매", "전",
					"매/층","전/층", "설명", "key" };

			int r = 0;
			Row hr = sheet.createRow(r++);
			for (int i = 0; i < headers.length; i++) {
				Cell c = hr.createCell(i);
				c.setCellValue(headers[i]);
				c.setCellStyle(headStyle);
			}

			// 본문
			for (int i = 0; i < jArr.length(); i++) {
				JSONObject complex = jArr.getJSONObject(i);

				String id = complex.optString("id", "");
				String gu = complex.optString("gu", "");
				String dong = complex.optString("dong", "");
				String aptNm = complex.optString("aptNm", "");
				String year = complex.optString("year", "");
				String sd = complex.optString("sd", "");
				String mCnt = complex.optString("mCnt", "");
				String jCnt = complex.optString("jCnt", "");

				JSONArray subInfo = complex.optJSONArray("subInfo");
				if (subInfo == null || subInfo.length() == 0)
					continue;

				JSONObject dealInfo = complex.optJSONObject("dealInfo");
				JSONObject rentInfo = complex.optJSONObject("rentInfo");

				for (int j = 0; j < subInfo.length(); j++) {
					JSONObject s = subInfo.optJSONObject(j);
					if (s == null)
						continue;

					String subType = s.optString("type", "");
					String subSpace = s.optString("space", "");
					String subInfoInfo = s.optString("info", "");
					String subKey = s.optString("key", "");

					JSONObject dealItem = findBySupplyType(dealInfo, subType);
					JSONObject rentItem = findBySupplyType(rentInfo, subType);

					String dealPriceStr = dealItem != null ? dealItem.optString("priceInfo", "") : "";
					String rentPriceStr = rentItem != null ? rentItem.optString("priceInfo", "") : "";
					String dealDesc = dealItem != null ? dealItem.optString("desc", "") : "";
					String dealFrInfoStr = dealItem != null ? dealItem.optString("floorInfo", "") : "";
					String rentFrInfoStr = rentItem != null ? rentItem.optString("floorInfo", "") : "";

					BigDecimal dealBD = toBD(dealPriceStr);
					BigDecimal rentBD = toBD(rentPriceStr);
					BigDecimal diffBD = (dealBD != null && rentBD != null) ? dealBD.subtract(rentBD) : null;

					Row row = sheet.createRow(r++);

					row.createCell(COL_ID).setCellValue(id);
					row.createCell(COL_GU).setCellValue(gu);
					row.createCell(COL_DONG).setCellValue(dong);
					row.createCell(COL_APTNM).setCellValue(aptNm);
					row.createCell(COL_YEAR).setCellValue(year);
					row.createCell(COL_SD).setCellValue(sd);
					row.createCell(COL_SPACE).setCellValue(subSpace);
					row.createCell(COL_TYPE).setCellValue(subType);
					row.createCell(COL_ROOM).setCellValue(subInfoInfo);
					row.createCell(COL_KEY).setCellValue(subKey);

					if (dealBD != null) {
						Cell dc = row.createCell(COL_DEAL);
						dc.setCellValue(dealBD.doubleValue());
						dc.setCellStyle(numStyle);
					} else {
						row.createCell(COL_DEAL).setCellValue("");
					}

					if (rentBD != null) {
						Cell rc = row.createCell(COL_RENT);
						rc.setCellValue(rentBD.doubleValue());
						rc.setCellStyle(numStyle);
					} else {
						row.createCell(COL_RENT).setCellValue("");
					}

					// 차액 먼저
					if (diffBD != null) {
						Cell dif = row.createCell(COL_DIFF);
						dif.setCellValue(diffBD.doubleValue());
						dif.setCellStyle(numStyle);
					} else {
						row.createCell(COL_DIFF).setCellValue("");
					}

					// 전세가율(%) 다음 (IFERROR(전세가/매매가,0))
					{
						int excelRow = row.getRowNum() + 1; // 1-based
						String dealAddr = CellReference.convertNumToColString(COL_DEAL) + excelRow;
						String rentAddr = CellReference.convertNumToColString(COL_RENT) + excelRow;

						Cell rateCell = row.createCell(COL_RATE);
						rateCell.setCellFormula("IFERROR(" + rentAddr + "/" + dealAddr + ",0)");
						rateCell.setCellStyle(pctStyle);
					}

					row.createCell(COL_M).setCellValue(mCnt);
					row.createCell(COL_J).setCellValue(jCnt);
					row.createCell(COL_M_FR).setCellValue(dealFrInfoStr);
					row.createCell(COL_J_FR).setCellValue(rentFrInfoStr);
					row.createCell(COL_DESC).setCellValue(dealDesc);
				}
			}

			// auto-size
			for (int i = 0; i < headers.length; i++)
				sheet.autoSizeColumn(i);

			try (FileOutputStream fos = new FileOutputStream(outXlsx.toFile())) {
				wb.write(fos);
			}
		}
	}

	/* ====== 유틸 ====== */

	private static String formatToEok(Object price) {
		if (price == null || price == JSONObject.NULL)
			return "";
		try {
			java.math.BigDecimal bd = new java.math.BigDecimal(price.toString());
			java.math.BigDecimal eok = bd
					.divide(new java.math.BigDecimal("100000000"), 3, java.math.RoundingMode.HALF_UP)
					.stripTrailingZeros();
			return eok.toPlainString();
		} catch (NumberFormatException e) {
			return "";
		}
	}

	/** "저층/고층 제외" 규칙: floorInfo가 '저' 또는 '고'로 시작하면 제외 */
	private static boolean chkFloor(String floorInfo) {
		if (floorInfo == null)
			return false;
		String s = floorInfo.trim();
		if (s.isEmpty())
			return false;
		return !(s.startsWith("저") || s.startsWith("1") || s.startsWith("2") || s.startsWith("3"));
	}

	private static String selText(Document doc, String css) {
		Element el = doc.selectFirst(css);
		return el != null ? el.text().trim() : "";
	}

	private static String addrSubstr(String text) {
		if (text == null)
			return "";
		String t = text.trim();
		return (t.length() >= 2) ? t.substring(0, t.length() - 1) : t;
	}

	private static String cvrtAptSaedae(String text) {
		if (text == null)
			return "";
		return text.replace("세대", "").replace(",", "").trim();
	}

	private static String cvrtAptYear(String text) {
		if (text == null)
			return "";
		int dot = text.indexOf('.');
		return (dot > 0) ? text.substring(0, dot).trim() : text.trim();
	}

	private static String clearAptNm(String aptNm) {
		return aptNm == null ? "" : aptNm.replace("VR투어", "").trim();
	}

	private static String getEntranceName(String type) {
		if ("10".equals(type)) {
			return "계";
		} else if ("20".equals(type)) {
			return "복";
		} else if ("30".equals(type)) {
			return "복합";
		} else {
			return type;
		}

	}

	/**
	 * targetId와 일치하는 "하나의 값"만 추출 - 요소가 JSONObject면: 그 객체의 "id" 필드가 targetId와 같을 때
	 * 해당 JSONObject 반환 - 요소가 String/Number면: 요소 자체가 targetId와 같을 때 그 값을 JSONObject로
	 * 감싸 반환({ "id": "<값>" }) 못 찾으면 null
	 */
	public static JSONObject pickOneById(JSONArray arr, String targetId) {
		if (arr == null || targetId == null)
			return null;
		String wanted = targetId.trim();

		for (int i = 0; i < arr.length(); i++) {
			Object node = arr.get(i);

			// 케이스 1: 객체 배열 [{ "id": "610", ... }, ...]
			if (node instanceof JSONObject) {
				JSONObject obj = (JSONObject) node;
				String id = obj.optString("id", null);
				if (id != null && id.trim().equals(wanted)) {
					return obj; // 해당 객체 그대로 반환
				}
				continue;
			}

			// 케이스 2: 값 배열 ["610", 123, ...]
			String val = String.valueOf(node).trim();
			if (val.equals(wanted)) {
				// 값만 있으면 간단히 감싸서 반환
				JSONObject wrapped = new JSONObject();
				wrapped.put("id", val);
				return wrapped;
			}
		}
		return null; // 못 찾음
	}

	/**
	 * data: 최상위 JSONArray targetId: 예) "111515" targetType: 예) "84A" return: 찾으면
	 * "info" 문자열(예: "계|3|2"), 없으면 null
	 */
	public static String findInfoByIdAndType(JSONArray data, String targetId, String targetType) {
		if (data == null || targetId == null || targetType == null)
			return null;

		String idWanted = targetId.trim();
		String typeWanted = targetType.trim();

		for (int i = 0; i < data.length(); i++) {
			JSONObject complex = data.optJSONObject(i);
			if (complex == null)
				continue;

			// id 매칭
			if (!idWanted.equals(complex.optString("id")))
				continue;

			// subInfo 배열에서 type 매칭
			JSONArray subInfo = complex.optJSONArray("subInfo");
			if (subInfo == null)
				return null;

			for (int j = 0; j < subInfo.length(); j++) {
				JSONObject sub = subInfo.optJSONObject(j);
				if (sub == null)
					continue;

				if (typeWanted.equals(sub.optString("type"))) {
					return sub.optString("info", null);
				}
			}
			// 해당 id 내에서 못 찾았으면 종료
			return null;
		}
		// id 자체가 없으면
		return null;
	}

	// ====== 유틸: dealInfo / rentInfo 에서 supplyType 으로 매칭되는 첫 항목 찾기 ======
	private static JSONObject findBySupplyType(JSONObject mapLike, String supplyType) {
		if (mapLike == null || supplyType == null)
			return null;
		for (Iterator<String> it = mapLike.keys(); it.hasNext();) {
			String k = it.next();
			JSONObject item = mapLike.optJSONObject(k);
			if (item == null)
				continue;
			if (supplyType.equals(item.optString("supplyType"))) {
				return item;
			}
		}
		return null;
	}

	// 숫자 문자열(BigDecimal). 공백/빈값/파싱실패 시 null
	private static BigDecimal toBD(String s) {
		if (s == null)
			return null;
		String t = s.trim();
		if (t.isEmpty())
			return null;
		try {
			return new BigDecimal(t);
		} catch (Exception e) {
			return null;
		}
	}
	
	// 파일명 추출
	public static String extractBaseName(String url) {
        try {
            String path = URI.create(url).getPath();           // /file/seong-buk-gu.xlsx
            if (path == null || path.isEmpty()) return "";

            String file = path.substring(path.lastIndexOf('/') + 1); // seong-buk-gu.xlsx

            // 쿼리/프래그먼트 제거 (예방용)
            int q = file.indexOf('?');
            if (q >= 0) file = file.substring(0, q);
            int h = file.indexOf('#');
            if (h >= 0) file = file.substring(0, h);

            // 확장자 제거
            int dot = file.lastIndexOf('.');
            return (dot >= 0) ? file.substring(0, dot) : file;
        } catch (Exception e) {
            return "";
        }
    }
	
	public static String getNowDate() {
		LocalDate today = LocalDate.now();
        // 원하는 포맷 정의 (yyyyMMdd)
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        // 포맷 적용
        return today.format(formatter);
        
	}

}