package cat.tecnocampus.fakegateway.charge;

import cat.tecnocampus.fakegateway.config.GatewayConfigStore;
import cat.tecnocampus.fakegateway.config.GatewayConfigStore.Settings;
import cat.tecnocampus.fakegateway.config.GatewayProperties;
import cat.tecnocampus.fakegateway.webhook.WebhookScheduler;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The single endpoint the application under test calls. */
@RestController
public class ChargeController {

    public record ChargeRequest(
            Long amountCents,
            String currency,
            String paymentToken,
            String reference,
            String callbackUrl) {
    }

    public record ChargeResponse(
            String providerRef,
            String status,
            long amountCents,
            String currency,
            String reference,
            String declineReason,
            Instant createdAt) {
    }

    public record GatewayError(String code, String message) {
    }

    private final ChargeStore charges;
    private final WebhookScheduler webhooks;
    private final GatewayConfigStore configStore;
    private final GatewayProperties properties;

    public ChargeController(ChargeStore charges,
                            WebhookScheduler webhooks,
                            GatewayConfigStore configStore,
                            GatewayProperties properties) {
        this.charges = charges;
        this.webhooks = webhooks;
        this.configStore = configStore;
        this.properties = properties;
    }

    @PostMapping("/charges")
    public ResponseEntity<?> charge(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ChargeRequest request) throws InterruptedException {

        if (request == null || request.amountCents() == null || request.amountCents() <= 0) {
            return ResponseEntity.badRequest()
                    .body(new GatewayError("invalid_request", "amountCents must be a positive integer"));
        }

        // Replays are answered before anything else, so a retry always gets the original answer
        // even while failure injection is on.
        var replay = charges.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return respond(replay.get(), true);
        }

        Settings settings = configStore.current();
        if (settings.failureRate() > 0 && ThreadLocalRandom.current().nextDouble() < settings.failureRate()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new GatewayError("gateway_unavailable",
                            "Injected failure, failureRate=" + settings.failureRate()));
        }

        Scenario scenario = Scenario.fromToken(request.paymentToken());

        // Nothing is recorded for a 503: as far as the gateway is concerned the charge never
        // happened, and the same idempotency key may legitimately come back.
        if (scenario == Scenario.SERVER_ERROR) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new GatewayError("gateway_unavailable", "Scenario " + scenario.token()));
        }

        if (scenario == Scenario.SLOW) {
            Thread.sleep(settings.slowResponseMillis());
        }

        ChargeStore.Result result =
                charges.createOrReplay(idempotencyKey, () -> newCharge(request, idempotencyKey, scenario));

        if (result.replay()) {
            return respond(result.charge(), true);
        }

        webhooks.scheduleFor(result.charge(), scenario, callbackUrlFor(request), settings);
        return respond(result.charge(), false);
    }

    private Charge newCharge(ChargeRequest request, String idempotencyKey, Scenario scenario) {
        Charge.Status status = switch (scenario) {
            case REJECTED -> Charge.Status.REJECTED;
            case PENDING_WEBHOOK, LATE_WEBHOOK -> Charge.Status.PENDING;
            default -> Charge.Status.SUCCEEDED;
        };

        return new Charge(
                "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                request.reference(),
                idempotencyKey,
                request.amountCents(),
                request.currency() != null ? request.currency() : "EUR",
                status,
                status == Charge.Status.REJECTED ? "card_declined" : null,
                scenario,
                Instant.now());
    }

    private String callbackUrlFor(ChargeRequest request) {
        if (request.callbackUrl() != null && !request.callbackUrl().isBlank()) {
            return request.callbackUrl();
        }
        return properties.getDefaultCallbackUrl();
    }

    private ResponseEntity<ChargeResponse> respond(Charge charge, boolean replay) {
        HttpStatus status = switch (charge.status()) {
            case SUCCEEDED -> HttpStatus.CREATED;
            case REJECTED -> HttpStatus.PAYMENT_REQUIRED;
            case PENDING -> HttpStatus.ACCEPTED;
        };

        return ResponseEntity.status(status)
                .header("X-Gateway-Idempotent-Replay", Boolean.toString(replay))
                .body(new ChargeResponse(
                        charge.providerRef(),
                        charge.status().name(),
                        charge.amountCents(),
                        charge.currency(),
                        charge.reference(),
                        charge.declineReason(),
                        charge.createdAt()));
    }
}
