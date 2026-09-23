package kz.hackalem.windagent;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Боевой режим: агент сам выпускает прогноз каждый день по расписанию. */
@Component
@ConditionalOnProperty(name = "windml.schedule.enabled", havingValue = "true")
public class DailyForecastJob {

    private static final Logger log = LoggerFactory.getLogger(DailyForecastJob.class);
    private final ForecastAgent agent;

    public DailyForecastJob(ForecastAgent agent) {
        this.agent = agent;
    }

    @Scheduled(cron = "${windml.schedule.cron}", zone = "Asia/Almaty")
    public void run() {
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Almaty")).toString();
        log.info("Daily forecast {}:\n{}", today, agent.forecast(today));
    }
}
