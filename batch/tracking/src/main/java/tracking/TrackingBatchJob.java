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

    private static final String DEFAULT_REGIONS = """
            tracking-list,tracking-jibang,daegu-suseong-gu,seocho-gu,
            gangnam-gu,songpa-gu,gwangjin-gu,seongdong-gu,
            yeongdeungpo-gu,yangcheon-gu,jongno-jung-gu,dongjak-gu,
            seong-buk-gu,dongdaemun-gu,seodaemun-gu,gwanak-gu,
            eunpyeong-gu,jungnang-gu,guro-gu,gwangmyeong-si-geumcheon-gu,
            suji-gu,giheung-gu,bundang-gu,anyang-si-dongan-gu,
            anyang-si-manan-gu,suwon-si-yeongtong-gu,dongtan,sujeong-jungwon-gu,
            bu-cheon,gunpo-si,guri-si,daejeon-seo-gu,
            daejeon-yuseong-gu,gwangju-buk-gu,busanjin-gu,yeonsu-gu,
            bupyeong-gu,cheonan,cheongju,jeonju,
            pohang-si-buk-gu
            """;

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
