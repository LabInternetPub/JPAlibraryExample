package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.domain.Receipt;
import cat.tecnocampus.library.domain.ReceiptMessage;
import cat.tecnocampus.library.payment.PaymentGateway;
import cat.tecnocampus.library.payment.PaymentGateway.Charge;
import cat.tecnocampus.library.payment.PaymentGateway.Outcome;
import cat.tecnocampus.library.payment.PaymentGateway.Reply;
import cat.tecnocampus.library.persistence.PurchaseRepository;
import cat.tecnocampus.library.persistence.ReceiptRepository;
import cat.tecnocampus.library.receipt.ReceiptMailbox;
import cat.tecnocampus.library.scenario.RaceWindow;
import cat.tecnocampus.library.scenario.TransitionPauses;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The purchase as it is usually written first: one method, one transaction, the call to the
 * payment provider in the middle, the message sent as one more step.
 *
 * <p>It works for one member at a time with a provider that answers quickly. It has four defects,
 * each one the subject of a lesson (and of a scenario that shows it with numbers):
 * <ol>
 *   <li><b>Idempotency by check-then-act.</b> "Is there a purchase with this key? No: create one."
 *       Ten simultaneous requests with the same key all look before any of them has committed:
 *       ten purchases, ten charges, ten receipts.</li>
 *   <li><b>The network call inside the transaction.</b> The transaction holds a database connection
 *       from its first statement to its commit, so every purchase waiting for the provider keeps
 *       one connection busy doing nothing. With a slow provider the pool runs out, and requests that
 *       never talk to the provider (the catalogue) fail too.</li>
 *   <li><b>A timeout treated as a failure.</b> Anything but a 201 gives the stock back. A provider
 *       that charged the card but answered after our timeout leaves a member who paid for books
 *       that are back on sale.</li>
 *   <li><b>The message sent before the commit.</b> If anything fails after it, the transaction rolls
 *       back (no purchase, no receipt), but the member has already been told about a receipt that
 *       does not exist, and the card has been charged.</li>
 * </ol>
 */
@Service
public class NaivePurchaseService {

    private final PurchaseReads reads;
    private final PurchaseRepository purchases;
    private final ReceiptRepository receipts;
    private final ShopStock stock;
    private final PaymentGateway gateway;
    private final ReceiptMailbox mailbox;
    private final EntityManager entityManager;
    private final RaceWindow raceWindow;
    private final TransitionPauses pauses;
    private final Clock clock;

    public NaivePurchaseService(PurchaseReads reads, PurchaseRepository purchases, ReceiptRepository receipts,
                                ShopStock stock, PaymentGateway gateway, ReceiptMailbox mailbox,
                                EntityManager entityManager, RaceWindow raceWindow, TransitionPauses pauses,
                                Clock clock) {
        this.reads = reads;
        this.purchases = purchases;
        this.receipts = receipts;
        this.stock = stock;
        this.gateway = gateway;
        this.mailbox = mailbox;
        this.entityManager = entityManager;
        this.raceWindow = raceWindow;
        this.pauses = pauses;
        this.clock = clock;
    }

    @Transactional
    public PurchaseAnswer purchase(PurchaseCommand command) {
        Member member = reads.member(command.memberEmail());

        // Defect 1: check, then act. Nothing stops another request from doing the same in between.
        Optional<Purchase> existing = purchases.findFirstByMemberIdAndIdempotencyKeyOrderByCreatedAt(
                member.getId(), command.idempotencyKey());
        raceWindow.pause();         // scenarios: every request has checked before any of them goes on
        if (existing.isPresent()) {
            Purchase purchase = existing.get();
            return PurchaseAnswer.of(purchase, receipts.findByPurchaseId(purchase.getId()).orElse(null));
        }

        Instant now = clock.instant();
        ShopStock.Taken taken = stock.take(command.isbns());
        Purchase purchase = Purchase.awaitingPayment(UUID.randomUUID(), member, command.idempotencyKey(),
                command.fingerprint(), taken.lines(), taken.skippedIsbns(), now);
        entityManager.persist(purchase);

        // Defect 2: this transaction (and its connection) stays open for as long as the provider takes.
        Reply reply = gateway.charge(new Charge(purchase.getTotalCents(), purchase.getCurrency(),
                command.paymentToken(), purchase.getId(), purchase.getId().toString()));

        if (reply.outcome() != Outcome.SUCCEEDED) {
            // Defect 3: a timeout is not a "no". The provider may have charged the card.
            purchase.markFailed();
            stock.release(purchase);
            return PurchaseAnswer.of(purchase, null);
        }

        purchase.markPaid(reply.providerRef());
        Receipt receipt = Receipt.issueFor(purchase, now);
        entityManager.persist(receipt);
        // Defect 4: the message leaves now. A rollback after this line cannot take it back.
        mailbox.send(new ReceiptMailbox.Mail(purchase.getId(), receipt.getNumber(), member.getEmail(),
                ReceiptMessage.text(receipt, purchase)));
        pauses.observe(TransitionPauses.RECEIPT_ISSUED, member.getId());   // scenarios: "something fails here"
        return PurchaseAnswer.of(purchase, receipt);
    }
}
