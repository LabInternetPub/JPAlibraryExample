package cat.tecnocampus.fakegateway.config;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** The knobs a test or a workshop turns at runtime, and the reset that puts them back. */
@Component
public class GatewayConfigStore {

    public record Settings(
            double failureRate,
            long slowResponseMillis,
            long lateWebhookMillis,
            long normalWebhookMillis) {
    }

    private final GatewayProperties defaults;
    private final AtomicReference<Settings> current = new AtomicReference<>();

    public GatewayConfigStore(GatewayProperties defaults) {
        this.defaults = defaults;
        this.current.set(defaultSettings());
    }

    public Settings current() {
        return current.get();
    }

    public Settings update(Double failureRate,
                           Long slowResponseMillis,
                           Long lateWebhookMillis,
                           Long normalWebhookMillis) {
        return current.updateAndGet(now -> new Settings(
                failureRate != null ? failureRate : now.failureRate(),
                slowResponseMillis != null ? slowResponseMillis : now.slowResponseMillis(),
                lateWebhookMillis != null ? lateWebhookMillis : now.lateWebhookMillis(),
                normalWebhookMillis != null ? normalWebhookMillis : now.normalWebhookMillis()));
    }

    public Settings reset() {
        Settings restored = defaultSettings();
        current.set(restored);
        return restored;
    }

    private Settings defaultSettings() {
        return new Settings(
                defaults.getFailureRate(),
                defaults.getSlowResponseMillis(),
                defaults.getLateWebhookMillis(),
                defaults.getNormalWebhookMillis());
    }
}
