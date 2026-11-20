package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.domain.PurchaseLine;
import cat.tecnocampus.library.domain.ShopExceptions.NothingAvailableException;
import cat.tecnocampus.library.persistence.BookOfferRepository;
import cat.tecnocampus.library.persistence.OfferRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Taking and giving back the copies a purchase buys. Shared by the naive and the better purchase:
 * the stock is not where those two differ.
 *
 * <p>{@code MANDATORY}: these methods only make sense inside the caller's transaction. Taken
 * copies must roll back with the purchase if anything fails after them.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ShopStock {

    private final BookOfferRepository offers;

    public ShopStock(BookOfferRepository offers) {
        this.offers = offers;
    }

    /** The lines bought (in the order requested) and the ISBNs skipped because they were sold out. */
    public record Taken(List<PurchaseLine> lines, List<String> skippedIsbns) {
    }

    /**
     * Takes one copy of each requested book that still has one.
     *
     * <p>Unknown ISBNs are refused before anything is taken (404). A sold-out book is skipped and the
     * purchase carries on with the others; if every book is sold out, the purchase is refused (409),
     * and the exception rolls back anything taken so far.
     */
    public Taken take(List<String> isbns) {
        List<OfferRow> rows = offers.findOffers(isbns);
        Set<String> offered = rows.stream().map(OfferRow::isbn).collect(Collectors.toSet());
        List<String> unknown = isbns.stream().filter(isbn -> !offered.contains(isbn)).toList();
        if (!unknown.isEmpty()) {
            throw new NotFoundException("Book for sale", unknown);
        }

        // In offer-id order (as the query returned them), the same for every purchase: no deadlock.
        List<PurchaseLine> lines = new ArrayList<>();
        for (OfferRow row : rows) {
            if (offers.takeOne(row.offerId()) == 1) {
                lines.add(new PurchaseLine(row.offerId(), row.isbn(), row.title(), row.priceCents()));
            }
        }
        if (lines.isEmpty()) {
            throw new NothingAvailableException(isbns);
        }
        Set<String> bought = lines.stream().map(PurchaseLine::getIsbn).collect(Collectors.toSet());
        lines.sort(Comparator.comparingInt(line -> isbns.indexOf(line.getIsbn())));
        List<String> skipped = isbns.stream().filter(isbn -> !bought.contains(isbn)).toList();
        return new Taken(lines, skipped);
    }

    /** Puts back one copy per line. Called only when it is proven that no money was taken. */
    public void release(Purchase purchase) {
        purchase.getLines().stream()
                .map(PurchaseLine::getOfferId)
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(offers::giveBack);
    }
}
