package cat.tecnocampus.library.payment;

import cat.tecnocampus.library.GatewayContainerConfiguration;
import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.payment.PaymentGateway.Charge;
import cat.tecnocampus.library.payment.PaymentGateway.Outcome;
import cat.tecnocampus.library.payment.PaymentGateway.Reply;
import cat.tecnocampus.library.shop.GatewayAdmin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The adapter against the real simulator: every answer becomes the right outcome, nothing throws. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GatewayContainerConfiguration.class})
class RestClientPaymentGatewayTest {

    @Autowired PaymentGateway gateway;
    @Autowired GatewayAdmin admin;
    @Autowired RestClient.Builder builder;
    @Autowired PaymentGatewayProperties properties;

    @BeforeEach
    void freshGateway() {
        admin.reset();
    }

    private static Charge charge(String token) {
        UUID reference = UUID.randomUUID();
        return new Charge(1950, "EUR", token, reference, reference.toString());
    }

    @Test
    void eachAnswerOfTheProviderHasItsOutcome() {
        // When
        Reply ok = gateway.charge(charge("tok_ok"));
        Reply rejected = gateway.charge(charge("tok_reject"));
        Reply failed = gateway.charge(charge("tok_fail"));
        Reply pending = gateway.charge(charge("tok_pending"));

        // Then
        assertThat(ok.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(ok.providerRef()).startsWith("ch_");
        assertThat(rejected.outcome()).isEqualTo(Outcome.DECLINED);
        assertThat(failed.outcome()).isEqualTo(Outcome.RETRYABLE);
        assertThat(pending.outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void theSameKeyIsChargedOnce() {
        // Given
        Charge charge = charge("tok_ok");

        // When
        Reply first = gateway.charge(charge);
        Reply again = gateway.charge(charge);

        // Then
        assertThat(again.providerRef()).isEqualTo(first.providerRef());
        assertThat(admin.chargesFor(charge.reference())).isEqualTo(1);
    }

    @Test
    void anAnswerLaterThanTheReadTimeoutIsUnknownAndTheProviderChargedAnyway() throws InterruptedException {
        // Given: the provider answers after 2.5 s, the client waits 2 s
        admin.slowResponseMillis(2_500);
        Charge charge = charge("tok_slow");

        // When
        long start = System.nanoTime();
        Reply reply = gateway.charge(charge);
        Duration waited = Duration.ofNanos(System.nanoTime() - start);

        // Then
        assertThat(reply.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(waited).isBetween(properties.readTimeout(), properties.readTimeout().plusSeconds(1));
        Thread.sleep(1_000);
        assertThat(admin.chargesFor(charge.reference())).isEqualTo(1);
    }

    @Test
    void aProviderThatCannotBeReachedIsRetryable() {
        // Given: nothing listens on port 1
        PaymentGateway unreachable = new RestClientPaymentGateway(builder,
                new PaymentGatewayProperties("http://localhost:1", Duration.ofSeconds(1), Duration.ofSeconds(2)));

        // When / Then
        assertThat(unreachable.charge(charge("tok_ok")).outcome()).isEqualTo(Outcome.RETRYABLE);
    }
}
