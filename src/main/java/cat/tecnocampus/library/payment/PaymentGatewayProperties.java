package cat.tecnocampus.library.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Where the payment provider is, and how long the shop waits to connect to it.
 *
 * <p>There is a connect timeout and <b>no read timeout</b>, on purpose. A connection that cannot be
 * opened proves the request never left, so nothing was charged and the purchase can fail cleanly.
 * A read timeout would give up on a request the provider already has, and may be charging: the
 * shop would not know whether the money moved. This example leaves that case out (see the lesson),
 * so once the request is sent, the shop waits for the answer.
 *
 * @param url            base URL of the provider
 * @param connectTimeout how long to wait for the TCP connection
 */
@ConfigurationProperties(prefix = "library.payment")
public record PaymentGatewayProperties(String url, Duration connectTimeout) {

    static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(1);

    public PaymentGatewayProperties {
        Objects.requireNonNull(url, "library.payment.url");
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) > 0) {
            throw new IllegalArgumentException("library.payment.connect-timeout must be positive and at most "
                    + MAX_CONNECT_TIMEOUT);
        }
    }
}
