package cat.tecnocampus.fakegateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Start-up defaults. Everything here except the secret and the callback URL can be changed at
 * runtime through {@code POST /admin/config}, and {@code POST /admin/reset} restores these values.
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** Shared secret for the HMAC signature sent with every webhook. */
    private String webhookSecret = "affora-webhook-secret";

    /** Where webhooks go when a charge request doesn't carry its own callbackUrl. */
    private String defaultCallbackUrl = "";

    /** Delay before answering a tok_slow charge. Eight seconds is the figure UC-19 describes. */
    private long slowResponseMillis = 8_000;

    /** Delay before a tok_late webhook is delivered, meant to land after the hold has expired. */
    private long lateWebhookMillis = 60_000;

    /** Delay before an ordinary webhook is delivered. */
    private long normalWebhookMillis = 500;

    /** Fraction of charges answered with 503 regardless of the token. Session 7 runs this at 0.3. */
    private double failureRate = 0.0;

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getDefaultCallbackUrl() {
        return defaultCallbackUrl;
    }

    public void setDefaultCallbackUrl(String defaultCallbackUrl) {
        this.defaultCallbackUrl = defaultCallbackUrl;
    }

    public long getSlowResponseMillis() {
        return slowResponseMillis;
    }

    public void setSlowResponseMillis(long slowResponseMillis) {
        this.slowResponseMillis = slowResponseMillis;
    }

    public long getLateWebhookMillis() {
        return lateWebhookMillis;
    }

    public void setLateWebhookMillis(long lateWebhookMillis) {
        this.lateWebhookMillis = lateWebhookMillis;
    }

    public long getNormalWebhookMillis() {
        return normalWebhookMillis;
    }

    public void setNormalWebhookMillis(long normalWebhookMillis) {
        this.normalWebhookMillis = normalWebhookMillis;
    }

    public double getFailureRate() {
        return failureRate;
    }

    public void setFailureRate(double failureRate) {
        this.failureRate = failureRate;
    }
}
