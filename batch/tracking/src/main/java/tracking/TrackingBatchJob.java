package tracking;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.configuration.support.DefaultBatchConfiguration;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(value = "trackingBatchConfiguration", proxyBeanMethods = false)
public class TrackingBatchJob extends DefaultBatchConfiguration {
    private static final String INPUT_BASE = "https://roy-fild.github.io/file/";

    private static final String DEFAULT_REGIONS = String.join(",",
            "tracking-list",                   // 수도권
            "tracking-jibang",                 // 지방
            "daegu-suseong-gu",                // 대구_수성구
            "seocho-gu",                       // 서초구
            "gangnam-gu",                      // 강남구
            "songpa-gu",                       // 송파구
            "gwangjin-gu",                     // 광진구
            "seongdong-gu",                    // 성동구
            "yeongdeungpo-gu",                 // 영등포구
            "yangcheon-gu",                    // 양천구
            "jongno-jung-gu",                  // 종로/중구
            "dongjak-gu",                      // 동작구
            "seong-buk-gu",                    // 성북구
            "dongdaemun-gu",                   // 동대분구
            "seodaemun-gu",                    // 서대문구
            "gwanak-gu",                       // 관악구
            "eunpyeong-gu",                    // 은평구
            "jungnang-gu",                     // 중랑구
            "guro-gu",                         // 구로구
            "gwangmyeong-si-geumcheon-gu",     // 광명시 - 금천구
            "suji-gu",                         // 수지구
            "giheung-gu",                      // 기흥구
            "bundang-gu",                      // 성남시_분당구
            "anyang-si-dongan-gu",             // 안양시_동안구
            "anyang-si-manan-gu",              // 안양시_만안구
            "suwon-si-yeongtong-gu",           // 수원시_영통구
            "dongtan",                         // 화성시_동탄
            "sujeong-jungwon-gu",              // 성남시_수정/증원구
            "bu-cheon",                        // 부천시
            "gunpo-si",                        // 군포시
            "guri-si",                         // 구리시
            "daejeon-seo-gu",                  // 대전_서구
            "daejeon-yuseong-gu",              // 대전_유성구
            "gwangju-buk-gu",                  // 광주_북구
            "busanjin-gu",                     // 부산진구
            "yeonsu-gu",                       // 인천시_연수구
            "bupyeong-gu",                     // 인천시_부평구
            "cheonan",                         // 천안시
            "cheongju",                        // 청주
            "jeonju",                          // 전주
            "pohang-si-buk-gu"                 // 포항시_북구
    );

    public static void main(String[] args) throws Exception {
        try (var context = new AnnotationConfigApplicationContext(TrackingBatchJob.class)) {
            var execution = context.getBean(JobOperator.class).start(
                    context.getBean("trackingBatchJob", Job.class),
                    new JobParametersBuilder().toJobParameters());
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                throw new IllegalStateException("시세 수집 실패: " + execution.getExitStatus());
            }
        }
    }

    @Bean
    Job trackingBatchJob(JobRepository repository, Step collectTrackingFiles) {
        return new JobBuilder("trackingBatchJob", repository).start(collectTrackingFiles).build();
    }

    @Bean
    Step collectTrackingFiles(JobRepository repository) {
        return new StepBuilder("collectTrackingFiles", repository)
                .tasklet((contribution, context) -> {
                    String regions = System.getenv().getOrDefault("TRACKING_REGIONS", DEFAULT_REGIONS);
                    Path output = Path.of(System.getenv().getOrDefault("TRACKING_OUTPUT_DIR", "../../file"));
                    String date = LocalDate.now(ZoneId.of("Asia/Seoul"))
                            .format(DateTimeFormatter.BASIC_ISO_DATE);

                    Set<String> regionKeys = new LinkedHashSet<>();
                    for (String entry : regions.split(",", -1)) {
                        String key = entry.trim();
                        if (!key.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                            throw new IllegalArgumentException("잘못된 지역 파일 키: " + key);
                        }
                        regionKeys.add(key);
                    }
                    for (String key : regionKeys) {
                        NaverLikeBrowser.collectRegion(INPUT_BASE + key + ".xlsx",
                                output.resolve(key + "_" + date + ".xlsx"));
                    }
                    return RepeatStatus.FINISHED;
                }, new ResourcelessTransactionManager())
                .build();
    }
}
