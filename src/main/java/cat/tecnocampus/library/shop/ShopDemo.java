package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.ReceiptMessage;
import cat.tecnocampus.library.persistence.BookOfferRepository;
import cat.tecnocampus.library.persistence.MemberRepository;
import cat.tecnocampus.library.persistence.PurchaseRepository;
import cat.tecnocampus.library.persistence.ReceiptMessageRepository;
import cat.tecnocampus.library.persistence.ReceiptRepository;
import cat.tecnocampus.library.receipt.ReceiptDispatcher;
import cat.tecnocampus.library.receipt.ReceiptMailbox;
import cat.tecnocampus.library.scenario.TransitionPauses;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * What the selling-books scenarios and tests share: putting the shop and the gateway back to a
 * known state, and reading the facts afterwards with fresh queries (each one its own transaction,
 * after everything has committed).
 */
@Component
public class ShopDemo {

    public static final String SOLD_OUT_ISBN = "978-84-000041";
    public static final String LAST_COPY_ISBN = "978-84-999999";
    static final int INITIAL_STOCK = 100;

    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final BookOfferRepository offers;
    private final PurchaseRepository purchases;
    private final ReceiptRepository receipts;
    private final ReceiptMessageRepository messages;
    private final MemberRepository members;
    private final ReceiptMailbox mailbox;
    private final ReceiptDispatcher dispatcher;
    private final TransitionPauses pauses;
    private final GatewayAdmin gateway;

    public ShopDemo(EntityManager entityManager, TransactionTemplate transaction, BookOfferRepository offers,
                    PurchaseRepository purchases, ReceiptRepository receipts, ReceiptMessageRepository messages,
                    MemberRepository members, ReceiptMailbox mailbox, ReceiptDispatcher dispatcher,
                    TransitionPauses pauses, GatewayAdmin gateway) {
        this.entityManager = entityManager;
        this.transaction = transaction;
        this.offers = offers;
        this.purchases = purchases;
        this.receipts = receipts;
        this.messages = messages;
        this.members = members;
        this.mailbox = mailbox;
        this.dispatcher = dispatcher;
        this.pauses = pauses;
        this.gateway = gateway;
    }

    /** ISBN of book n of the shop (1–40 are for sale, 100 copies each). */
    public static String isbn(int n) {
        return "978-84-%06d".formatted(n);
    }

    /** No purchases, receipts or messages; the initial stock; an empty mailbox; a fresh gateway. */
    public void reset() {
        pauses.disarm();
        dispatcher.simulateCrashAfterCommit(false);
        transaction.executeWithoutResult(status -> resetShop());
        mailbox.clear();
        gateway.reset();
    }

    private void resetShop() {
        // JPQL bulk statements: no entities loaded. Hibernate also empties the purchases'
        // element-collection tables (lines, skipped ISBNs).
        entityManager.createQuery("delete from ReceiptMessage").executeUpdate();
        entityManager.createQuery("delete from Receipt").executeUpdate();
        entityManager.createQuery("delete from PurchaseKey").executeUpdate();
        entityManager.createQuery("delete from Purchase").executeUpdate();
        entityManager.createQuery("update BookOffer o set o.stock = :stock")
                .setParameter("stock", INITIAL_STOCK).executeUpdate();
        setStock(SOLD_OUT_ISBN, 0);
        setStock(LAST_COPY_ISBN, 1);
    }

    private void setStock(String isbn, int stock) {
        entityManager.createQuery("update BookOffer o set o.stock = :stock where o.book.id in "
                        + "(select b.id from Book b where b.isbn = :isbn)")
                .setParameter("stock", stock).setParameter("isbn", isbn).executeUpdate();
    }

    public ReceiptMailbox mailbox() {
        return mailbox;
    }

    public GatewayAdmin gateway() {
        return gateway;
    }

    public UUID memberId(String email) {
        return members.findByEmail(email).orElseThrow().getId();
    }

    public int stock(String isbn) {
        return offers.stockOf(isbn).orElseThrow();
    }

    /** Copies of this book sold or held by purchases since the reset. */
    public int taken(String isbn) {
        int initial = SOLD_OUT_ISBN.equals(isbn) ? 0 : LAST_COPY_ISBN.equals(isbn) ? 1 : INITIAL_STOCK;
        return initial - stock(isbn);
    }

    public long purchasesOf(String email) {
        return purchases.countByMemberId(memberId(email));
    }

    public long receiptsOf(String email) {
        return receipts.countByMemberId(memberId(email));
    }

    public long pendingMessages() {
        return messages.countByStatus(ReceiptMessage.Status.PENDING);
    }

    public long sentMessages() {
        return messages.countByStatus(ReceiptMessage.Status.SENT);
    }

    /** Messages the member actually received (the mailbox), whatever the database says. */
    public int mailsTo(String email) {
        return mailbox.sentTo(email).size();
    }

    @Transactional(readOnly = true)
    public String status(UUID purchaseId) {
        return purchases.findById(purchaseId).map(p -> p.getStatus().name()).orElse("does not exist");
    }
}
