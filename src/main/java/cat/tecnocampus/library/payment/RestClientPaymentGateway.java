package cat.tecnocampus.library.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;

/**
 * The payment provider over HTTP, with Spring's {@link RestClient}.
 *
 * <p>Three decisions an assistant usually gets wrong, and that this class makes explicit:
 * <ol>
 *   <li><b>The client is configured, not left at its defaults.</b> The builder Spring Boot provides
 *       is used (so its JSON converters and metrics apply), with our own request factory: a 1 s
 *       connect timeout ({@link PaymentGatewayProperties}, which also explains why there is no
 *       read timeout).</li>
 *   <li><b>Statuses are answers, not errors.</b> By default {@code retrieve()} throws on 4xx and
 *       5xx, and a generic {@code catch} makes a 402 and a 503 the same thing.
 *       {@code exchange(...)} hands us every response, and each status gets its own outcome.</li>
 *   <li><b>A 201 is believed only if it is ours.</b> Amount, currency and reference must match
 *       the charge, and a provider reference must be present.</li>
 * </ol>
 * The class never throws: a connection that cannot be opened is an outcome too.
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
        this.restClient = builder
                .baseUrl(properties.url())
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
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
        } catch (RestClientException e) {
            // No usable answer: the provider could not be reached, or its body could not be read.
            return failed(charge, e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    private Reply classify(Charge charge, ConvertibleClientHttpResponse response) throws IOException {
        HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
        if (status == HttpStatus.PAYMENT_REQUIRED) {
            return new Reply(Outcome.DECLINED, null, "402");
        }
        if (status != HttpStatus.CREATED) {
            // 503 above all: the provider failed and recorded nothing.
            return failed(charge, String.valueOf(response.getStatusCode().value()));
        }
        ChargeAnswer answer = response.bodyTo(ChargeAnswer.class);
        if (answer == null || !"SUCCEEDED".equals(answer.status())
                || answer.amountCents() == null || answer.amountCents() != charge.amountCents()
                || !charge.currency().equals(answer.currency())
                || !charge.reference().toString().equals(answer.reference())
                || answer.providerRef() == null || answer.providerRef().isBlank()) {
            return failed(charge, "201 that does not match the charge");
        }
        return new Reply(Outcome.SUCCEEDED, answer.providerRef(), "201");
    }

    private static Reply failed(Charge charge, String detail) {
        log.warn("Payment failed for purchase {}: {}", charge.reference(), detail);
        return new Reply(Outcome.FAILED, null, detail);
    }
}
