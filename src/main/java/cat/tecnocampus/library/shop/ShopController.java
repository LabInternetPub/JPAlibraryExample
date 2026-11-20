package cat.tecnocampus.library.shop;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Buying books: naive and better, side by side. The {@code Idempotency-Key} header is mandatory
 * (Spring answers 400 without it). The answer's status follows the purchase: 201 paid, 202 not
 * finished yet, 402 declined, 503 provider failed; see {@link PurchaseAnswer}.
 */
@RestController
public class ShopController {

    /** The body of a purchase request. */
    public record PurchaseRequest(@NotBlank String memberEmail, @NotEmpty List<@NotBlank String> isbns,
                                  @NotBlank String paymentToken) {
    }

    private final NaivePurchaseService naive;
    private final PurchaseService better;
    private final PurchaseReads reads;

    public ShopController(NaivePurchaseService naive, PurchaseService better, PurchaseReads reads) {
        this.naive = naive;
        this.better = better;
        this.reads = reads;
    }

    @PostMapping("/naive/purchases")
    public ResponseEntity<PurchaseResponse> naive(@RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody PurchaseRequest request) {
        return respond(naive.purchase(command(key, request)));
    }

    @PostMapping("/better/purchases")
    public ResponseEntity<PurchaseResponse> better(@RequestHeader("Idempotency-Key") String key,
                                                   @Valid @RequestBody PurchaseRequest request) {
        return respond(better.purchase(command(key, request)));
    }

    @GetMapping("/purchases/{id}")
    public PurchaseResponse get(@PathVariable UUID id) {
        return reads.get(id);
    }

    private static PurchaseCommand command(String key, PurchaseRequest request) {
        return new PurchaseCommand(request.memberEmail(), key, request.isbns(), request.paymentToken());
    }

    private static ResponseEntity<PurchaseResponse> respond(PurchaseAnswer answer) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(answer.httpStatus());
        if (answer.httpStatus() == 201 || answer.httpStatus() == 202) {
            builder.location(URI.create("/purchases/" + answer.body().id()));
        }
        return builder.body(answer.body());
    }
}
