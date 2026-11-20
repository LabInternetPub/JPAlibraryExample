package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.Member;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.Purchase;
import cat.tecnocampus.library.persistence.MemberRepository;
import cat.tecnocampus.library.persistence.PurchaseRepository;
import cat.tecnocampus.library.persistence.ReceiptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reading a purchase, and the answer a purchase request gets. */
@Service
public class PurchaseReads {

    private final PurchaseRepository purchases;
    private final ReceiptRepository receipts;
    private final MemberRepository members;

    public PurchaseReads(PurchaseRepository purchases, ReceiptRepository receipts, MemberRepository members) {
        this.purchases = purchases;
        this.receipts = receipts;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public PurchaseAnswer answer(UUID purchaseId) {
        Purchase purchase = purchases.findById(purchaseId)
                .orElseThrow(() -> new NotFoundException("Purchase", purchaseId));
        return PurchaseAnswer.of(purchase, receipts.findByPurchaseId(purchaseId).orElse(null));
    }

    @Transactional(readOnly = true)
    public PurchaseResponse get(UUID purchaseId) {
        return answer(purchaseId).body();
    }

    Member member(String email) {
        return members.findByEmail(email).orElseThrow(() -> new NotFoundException("Member", email));
    }
}
