package cat.tecnocampus.fakegateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakePaymentGatewayTest.WebhookReceiverConfiguration.class)
class FakePaymentGatewayTest {

    private static final String SECRET = "affora-webhook-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private WebhookReceiver receiver;

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private RestClient client;

    @BeforeEach
    void resetGateway() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        receiver.received.clear();
        post("/admin/reset", null, null);
        post("/admin/config", """
                {"normalWebhookMillis": 100}
                """, null);
    }

    @Test
    void successfulChargeIsCreated() throws Exception {
        Response response = post("/charges", body("tok_ok"), "key-success");

        assertThat(response.status()).isEqualTo(201);
        assertThat(json(response).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(json(response).get("providerRef").asString()).startsWith("ch_");
    }

    @Test
    void declinedCardAnswersPaymentRequired() throws Exception {
        Response response = post("/charges", body("tok_reject"), "key-reject");

        assertThat(response.status()).isEqualTo(402);
        assertThat(json(response).get("status").asString()).isEqualTo("REJECTED");
        assertThat(json(response).get("declineReason").asString()).isEqualTo("card_declined");
    }

    @Test
    void serverErrorIsRetryableAndRecordsNothing() throws Exception {
        assertThat(post("/charges", body("tok_fail"), "key-fail").status()).isEqualTo(503);

        assertThat(json(get("/admin/charges")).size()).isZero();
    }

    @Test
    void sameIdempotencyKeyChargesOnlyOnce() throws Exception {
        Response first = post("/charges", body("tok_ok"), "key-repeat");
        Response second = post("/charges", body("tok_ok"), "key-repeat");

        assertThat(first.status()).isEqualTo(second.status());
        assertThat(json(first).get("providerRef").asString())
                .isEqualTo(json(second).get("providerRef").asString());
        assertThat(json(get("/admin/charges")).size()).isEqualTo(1);
    }

    @Test
    void duplicateWebhookDeliversTheSameEventTwice() throws Exception {
        post("/charges", body("tok_duplicate"), "key-duplicate");

        List<Received> events = awaitWebhooks(2);

        assertThat(json(events.get(0).body()).get("eventId").asString())
                .isEqualTo(json(events.get(1).body()).get("eventId").asString());
    }

    @Test
    void outOfOrderWebhookDeliversTheNewerSequenceFirst() throws Exception {
        post("/charges", body("tok_out_of_order"), "key-out-of-order");

        List<Received> events = awaitWebhooks(2);

        assertThat(json(events.get(0).body()).get("sequence").asInt()).isEqualTo(2);
        assertThat(json(events.get(1).body()).get("sequence").asInt()).isEqualTo(1);
    }

    @Test
    void webhookIsSignedOverTheRawBody() throws Exception {
        post("/charges", body("tok_pending"), "key-pending");

        Received event = awaitWebhooks(1).getFirst();

        assertThat(event.signature()).isEqualTo(hmac(event.body()));
    }

    @Test
    void slowScenarioTakesAtLeastTheConfiguredDelay() {
        post("/admin/config", """
                {"slowResponseMillis": 400}
                """, null);

        Instant start = Instant.now();
        Response response = post("/charges", body("tok_slow"), "key-slow");

        assertThat(response.status()).isEqualTo(201);
        assertThat(Duration.between(start, Instant.now())).isGreaterThanOrEqualTo(Duration.ofMillis(400));
    }

    private String body(String paymentToken) {
        return """
                {
                  "amountCents": 12500,
                  "currency": "EUR",
                  "paymentToken": "%s",
                  "reference": "hold-1",
                  "callbackUrl": "http://localhost:%d/test/webhooks"
                }
                """.formatted(paymentToken, port);
    }

    private List<Received> awaitWebhooks(int expected) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            if (receiver.received.size() >= expected) {
                return List.copyOf(receiver.received);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Expected " + expected + " webhooks, received " + receiver.received.size());
    }

    private static String hmac(String rawBody) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode json(Response response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private Response post(String path, String body, String idempotencyKey) {
        var request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        var spec = body != null ? request.body(body) : request.body("{}");
        return spec.exchange((req, res) -> new Response(res.getStatusCode().value(), asString(res.getBody())), false);
    }

    private String get(String path) {
        return client.get().uri(path)
                .exchange((req, res) -> asString(res.getBody()), false);
    }

    private static String asString(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private record Response(int status, String body) {
    }

    record Received(String signature, String body) {
    }

    @TestConfiguration
    static class WebhookReceiverConfiguration {

        @Bean
        WebhookReceiver webhookReceiver() {
            return new WebhookReceiver();
        }
    }

    @RestController
    static class WebhookReceiver {

        private final List<Received> received = new CopyOnWriteArrayList<>();

        @PostMapping("/test/webhooks")
        ResponseEntity<Void> receive(@RequestHeader("X-Gateway-Signature") String signature,
                                     @RequestBody String body) {
            received.add(new Received(signature, body));
            return ResponseEntity.noContent().build();
        }
    }
}
