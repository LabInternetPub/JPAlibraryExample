package cat.tecnocampus.library.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;

/**
 * The payment provider over HTTP, with Spring's {@link RestClient}.
 *
 * <p>Three decisions an assistant usually gets wrong, and that this class makes explicit:
 * <ol>
 *   <li><b>Timeouts.</b> The builder Spring Boot provides is used (so its JSON converters and
 *       metrics apply), but with our own request factory: a 1 s connect timeout and a read timeout
 *       of at most 3 s ({@link PaymentGatewayProperties}).</li>
 *   <li><b>Statuses are answers, not errors.</b> By default {@code retrieve()} throws on 4xx and
 *       5xx; a 402 caught as a generic exception ends up as "unknown", and a 503 as "declined".
 *       {@code exchange(...)} hands us every response, and each status gets its own outcome.</li>
 *   <li><b>A 201 is believed only if it is ours.</b> Amount, currency and reference must match
 *       the charge, and a provider reference must be present.</li>
 * </ol>
 * The class never throws: a timeout or a broken connection is an outcome too.
 */
@Component
@EnableConfigurationProperties(PaymentGatewayProperties.class)
public class RestClientPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RestClientPaymentGateway.class);

    private final RestClient restClient;

    public RestClientPaymentGateway(RestClient.Builder builder, PaymentGatewayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .build();
    }

    /** The body the provider expects. */
    record ChargeBody(long amountCents, String currency, String paymentToken, String reference) {
    }

    /** The fields of the provider's answer that the shop checks. */
    record ChargeAnswer(String providerRef, String status, Long amountCents, String currency, String reference) {
    }

    @Override
    public Reply charge(Charge charge) {
        try {
            return restClient.post()
                    .uri("/charges")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", charge.idempotencyKey())
                    .body(new ChargeBody(charge.amountCents(), charge.currency(), charge.paymentToken(),
                            charge.reference().toString()))
                    .exchange((request, response) -> classify(charge, response));
        } catch (ResourceAccessException e) {
            return connectionProblem(charge, e);
        } catch (RestClientException e) {
            // A body that could not be read or converted: we do not know what the provider did.
            return unknown(charge, e.getClass().getSimpleName());
        }
    }

    private Reply classify(Charge charge, ConvertibleClientHttpResponse response) throws IOException {
        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
        if (status == HttpStatus.PAYMENT_REQUIRED) {
            return new Reply(Outcome.DECLINED, null, "402");
        }
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            return new Reply(Outcome.RETRYABLE, null, "503");
        }
        if (status != HttpStatus.CREATED) {
            // 202 PENDING included: the outcome will be decided later, we cannot know it now.
            return unknown(charge, String.valueOf(response.getStatusCode().value()));
        }
        ChargeAnswer answer = response.bodyTo(ChargeAnswer.class);
        if (answer == null || !"SUCCEEDED".equals(answer.status())
                || answer.amountCents() == null || answer.amountCents() != charge.amountCents()
                || !charge.currency().equals(answer.currency())
                || !charge.reference().toString().equals(answer.reference())
                || answer.providerRef() == null || answer.providerRef().isBlank()) {
            return unknown(charge, "201 that does not match the charge");
        }
        return new Reply(Outcome.SUCCEEDED, answer.providerRef(), "201");
    }

    /**
     * No HTTP answer. If the connection was never opened, the request never left: nothing can have
     * been charged, so it is safe to retry. Otherwise (a read timeout, a reset after sending) the
     * provider may have charged: unknown.
     */
    private Reply connectionProblem(Charge charge, ResourceAccessException e) {
        // The whole chain: the JDK reports a refused connection as a ConnectException wrapping a
        // ClosedChannelException, so the innermost cause alone would hide it.
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                log.warn("Payment provider unreachable for purchase {}: {}", charge.reference(),
                        cause.getClass().getSimpleName());
                return new Reply(Outcome.RETRYABLE, null, cause.getClass().getSimpleName());
            }
        }
        return unknown(charge, e.getMostSpecificCause().getClass().getSimpleName());
    }

    private static Reply unknown(Charge charge, String detail) {
        log.warn("Payment outcome unknown for purchase {}: {}", charge.reference(), detail);
        return new Reply(Outcome.UNKNOWN, null, detail);
    }
}
