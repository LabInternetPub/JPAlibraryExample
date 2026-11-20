package cat.tecnocampus.library.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Where the payment provider is, and how long the shop waits for it.
 *
 * <p>Both timeouts are mandatory and bounded. The JDK client that {@code RestClient} uses here has
 * no read timeout unless one is set: without it, a provider that never answers keeps the request
 * (and whatever it holds) forever. The bounds keep the worst attempt (3 calls) inside the payment
 * lease of {@code Purchase.PAYMENT_LEASE} (15 s): 3 × (1 s + 3 s) + backoff.
 *
 * @param url            base URL of the provider
 * @param connectTimeout how long to wait for the TCP connection
 * @param readTimeout    how long to wait for the answer once the request is sent
 */
@ConfigurationProperties(prefix = "library.payment")
public record PaymentGatewayProperties(String url, Duration connectTimeout, Duration readTimeout) {

    static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(1);
    static final Duration MAX_READ_TIMEOUT = Duration.ofSeconds(3);

    public PaymentGatewayProperties {
        Objects.requireNonNull(url, "library.payment.url");
        requireWithin(connectTimeout, MAX_CONNECT_TIMEOUT, "library.payment.connect-timeout");
        requireWithin(readTimeout, MAX_READ_TIMEOUT, "library.payment.read-timeout");
    }

    private static void requireWithin(Duration timeout, Duration max, String name) {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(max) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most " + max);
        }
    }
}
