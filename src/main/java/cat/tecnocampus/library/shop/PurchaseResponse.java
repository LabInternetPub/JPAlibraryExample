package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.domain.PurchaseLine;
import cat.tecnocampus.library.domain.PurchaseStatus;
import cat.tecnocampus.library.domain.Receipt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the member sees of a purchase. No member data (the member already knows who they are) and
 * no payment token.
 *
 * @param receipt only for a paid purchase
 */
public record PurchaseResponse(UUID id, PurchaseStatus status, List<Line> lines, List<String> skippedIsbns,
                               long totalCents, String currency, ReceiptView receipt) {

    public record Line(String isbn, String title, long priceCents) {
    }

    public record ReceiptView(String number, String providerRef, Instant issuedAt) {
    }

    static PurchaseResponse of(Purchase purchase, Receipt receipt) {
        List<Line> lines = purchase.getLines().stream().map(PurchaseResponse::line).toList();
        ReceiptView view = receipt == null ? null
                : new ReceiptView(receipt.getNumber(), receipt.getProviderRef(), receipt.getIssuedAt());
        return new PurchaseResponse(purchase.getId(), purchase.getStatus(), lines,
                List.copyOf(purchase.getSkippedIsbns()), purchase.getTotalCents(), purchase.getCurrency(), view);
    }

    private static Line line(PurchaseLine line) {
        return new Line(line.getIsbn(), line.getTitle(), line.getPriceCents());
    }
}
