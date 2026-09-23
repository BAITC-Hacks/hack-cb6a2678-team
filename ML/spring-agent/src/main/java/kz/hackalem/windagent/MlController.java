package kz.hackalem.windagent;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Direct backend → FastAPI → ML contract, without an LLM call. */
@RestController
@RequestMapping("/ml")
public class MlController {
    private final RestClient http;

    public MlController(@Value("${windml.base-url}") String baseUrl,
                        @Value("${windml.connect-timeout-seconds:5}") int connectSeconds,
                        @Value("${windml.read-timeout-seconds:120}") int readSeconds) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(connectSeconds));
        factory.setReadTimeout(Duration.ofSeconds(readSeconds));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public record ForecastRequest(String site, LocalDate issue_date, String weather_source) { }

    @PostMapping(value = "/forecast", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> forecast(@RequestBody ForecastRequest body) {
        if (body.issue_date() == null) {
            return ResponseEntity.badRequest().body("{\"detail\":\"issue_date is required\"}");
        }
        // Send the date as ISO text regardless of RestClient's Jackson defaults.
        var request = Map.of("site", body.site() == null ? "turbine_1" : body.site(),
                "issue_date", body.issue_date().toString(),
                "weather_source", body.weather_source() == null ? "previous_runs" : body.weather_source());
        return http.post().uri("/v1/forecast").body(request).retrieve().toEntity(String.class);
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> models() {
        return http.get().uri("/v1/models").retrieve().toEntity(String.class);
    }

    @GetMapping(value = "/models/{site}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> model(@PathVariable String site) {
        return http.get().uri("/v1/models/{site}", site).retrieve().toEntity(String.class);
    }

    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<String> upstreamError(RestClientResponseException error) {
        return ResponseEntity.status(error.getStatusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(error.getResponseBodyAsString());
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<String> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"ML service unavailable or timed out\"}");
    }
}
