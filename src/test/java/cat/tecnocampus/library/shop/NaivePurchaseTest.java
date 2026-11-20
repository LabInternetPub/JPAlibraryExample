package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.GatewayContainerConfiguration;
import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.PurchaseStatus;
import cat.tecnocampus.library.scenario.ConcurrentRun;
import cat.tecnocampus.library.scenario.ConcurrentRun.Attempt;
import cat.tecnocampus.library.scenario.RaceWindow;
import cat.tecnocampus.library.scenario.TransitionPauses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static cat.tecnocampus.library.shop.ShopDemo.isbn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The naive purchase works one request at a time, and fails exactly where the lessons say. These
 * tests pin the failures down, so the lesson's claims are checked on every build. (The pool
 * starvation is in {@link ShopScenariosTest}.)
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GatewayContainerConfiguration.class})
class NaivePurchaseTest {

    private static final String ANNA = "member01@library.test";

    @Autowired NaivePurchaseService naive;
    @Autowired ShopDemo demo;
    @Autowired RaceWindow raceWindow;
    @Autowired TransitionPauses pauses;

    @BeforeEach
    void freshShop() {
        demo.reset();
    }

    @Test
    void oneRequestAtATimeItWorks() {
        PurchaseCommand command = new PurchaseCommand(ANNA, "k1", List.of(isbn(1)), "tok_ok");

        PurchaseAnswer first = naive.purchase(command);
        PurchaseAnswer retry = naive.purchase(command);

        assertThat(first.httpStatus()).isEqualTo(201);
        assertThat(retry).isEqualTo(first);
        assertThat(demo.gateway().charges()).hasSize(1);
    }

    @Test
    void tenSimultaneousRequestsWithOneKeyBuyTenTimes() {
        // Given
        PurchaseCommand command = new PurchaseCommand(ANNA, "same", List.of(isbn(1)), "tok_ok");
        raceWindow.arm(10);

        // When
        List<Attempt<PurchaseAnswer>> attempts;
        try {
            attempts = ConcurrentRun.run(10, i -> naive.purchase(command));
        } finally {
            raceWindow.disarm();
        }

        // Then
        assertThat(attempts).allMatch(Attempt::succeeded);
        assertThat(demo.purchasesOf(ANNA)).isEqualTo(10);
        assertThat(demo.gateway().charges()).hasSize(10);
        assertThat(demo.mailsTo(ANNA)).isEqualTo(10);
    }

    @Test
    void aLateAnswerPutsPaidBooksBackOnSale() throws InterruptedException {
        // Given
        demo.gateway().slowResponseMillis(2_500);

        // When
        PurchaseAnswer answer = naive.purchase(new PurchaseCommand(ANNA, "late", List.of(isbn(1)), "tok_slow"));
        Thread.sleep(1_000);

        // Then
        assertThat(answer.body().status()).isEqualTo(PurchaseStatus.PAYMENT_FAILED);
        assertThat(demo.taken(isbn(1))).isZero();
        assertThat(demo.gateway().charges()).hasSize(1);       // the card was charged
    }

    @Test
    void aFailureAfterTheMessageLeavesAMemberToldAboutAReceiptThatDoesNotExist() {
        // Given
        pauses.failAt(TransitionPauses.RECEIPT_ISSUED, demo.memberId(ANNA));

        // When
        assertThatThrownBy(() -> naive.purchase(new PurchaseCommand(ANNA, "k1", List.of(isbn(1)), "tok_ok")))
                .isInstanceOf(TransitionPauses.InjectedFailure.class);

        // Then
        assertThat(demo.purchasesOf(ANNA)).isZero();
        assertThat(demo.receiptsOf(ANNA)).isZero();
        assertThat(demo.mailsTo(ANNA)).isEqualTo(1);
        assertThat(demo.gateway().charges()).hasSize(1);
    }
}
