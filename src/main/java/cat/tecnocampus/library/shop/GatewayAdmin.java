package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.payment.PaymentGatewayProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * The fake gateway's control surface ({@code /admin}), for scenarios and tests only: reset it,
 * make it slow or failing, and count the charges it really took.
 *
 * <p>The purchase code never uses this class. A real provider has no such API, and a purchase that
 * asked the provider "did you charge?" this way would be testing the simulator, not itself.
 */
@Component
public class GatewayAdmin {

    /** A charge as the gateway recorded it. */
    public record GatewayCharge(String providerRef, String reference, String idempotencyKey, long amountCents,
                                String status) {
    }

    private final RestClient restClient;

    public GatewayAdmin(RestClient.Builder builder, PaymentGatewayProperties properties) {
        this.restClient = builder.baseUrl(properties.url()).build();
    }

    /** No charges, default settings (no failures, 8 s slow answers). */
    public void reset() {
        restClient.post().uri("/admin/reset").retrieve().toBodilessEntity();
    }

    /** How long a {@code tok_slow} charge takes to answer. */
    public void slowResponseMillis(long millis) {
        restClient.post().uri("/admin/config").body(Map.of("slowResponseMillis", millis)).retrieve().toBodilessEntity();
    }

    /** Fraction of charges answered 503, whatever the token. */
    public void failureRate(double rate) {
        restClient.post().uri("/admin/config").body(Map.of("failureRate", rate)).retrieve().toBodilessEntity();
    }

    /** Every charge the gateway took. "One purchase, one charge" is checked here. */
    public List<GatewayCharge> charges() {
        return restClient.get().uri("/admin/charges").retrieve()
                .body(new ParameterizedTypeReference<List<GatewayCharge>>() { });
    }

    /** The charges of one purchase (its id is the charge's reference). */
    public long chargesFor(Object purchaseId) {
        return charges().stream().filter(charge -> purchaseId.toString().equals(charge.reference())).count();
    }
}
