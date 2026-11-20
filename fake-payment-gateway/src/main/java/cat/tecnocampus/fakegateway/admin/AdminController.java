package cat.tecnocampus.fakegateway.admin;

import cat.tecnocampus.fakegateway.charge.Charge;
import cat.tecnocampus.fakegateway.charge.ChargeStore;
import cat.tecnocampus.fakegateway.config.GatewayConfigStore;
import cat.tecnocampus.fakegateway.config.GatewayConfigStore.Settings;
import cat.tecnocampus.fakegateway.webhook.WebhookQueue;
import cat.tecnocampus.fakegateway.webhook.WebhookQueue.DeliveryAttempt;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control surface for tests and workshops. A real provider has nothing like it, which is why
 * it lives under its own prefix: no application code should ever call these.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    public record ConfigUpdate(
            Double failureRate,
            Long slowResponseMillis,
            Long lateWebhookMillis,
            Long normalWebhookMillis) {
    }

    private final ChargeStore charges;
    private final WebhookQueue webhooks;
    private final GatewayConfigStore configStore;

    public AdminController(ChargeStore charges, WebhookQueue webhooks, GatewayConfigStore configStore) {
        this.charges = charges;
        this.webhooks = webhooks;
        this.configStore = configStore;
    }

    /** Wipes charges, pending webhooks and the delivery log, and restores the start-up settings. */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        charges.clear();
        webhooks.clear();
        configStore.reset();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/config")
    public Settings config() {
        return configStore.current();
    }

    /** Partial update: whatever is left out keeps its current value. */
    @PostMapping("/config")
    public Settings updateConfig(@RequestBody ConfigUpdate update) {
        return configStore.update(
                update.failureRate(),
                update.slowResponseMillis(),
                update.lateWebhookMillis(),
                update.normalWebhookMillis());
    }

    /** Every charge taken. This is how a test answers "one order, one charge". */
    @GetMapping("/charges")
    public List<Charge> charges() {
        return charges.all();
    }

    /** Every webhook delivery attempted, successful or not, plus how many are still queued. */
    @GetMapping("/webhooks")
    public WebhookReport webhooks() {
        return new WebhookReport(webhooks.pendingCount(), webhooks.attempts());
    }

    public record WebhookReport(int pending, List<DeliveryAttempt> attempts) {
    }
}
