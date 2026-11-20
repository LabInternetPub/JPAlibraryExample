package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.domain.ShopExceptions.InvalidPurchaseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PurchaseCommandTest {

    @Test
    void theOrderOfTheIsbnsDoesNotChangeTheFingerprint() {
        PurchaseCommand one = new PurchaseCommand("a@x", "k", List.of("1", "2"), "tok_ok");
        PurchaseCommand other = new PurchaseCommand("a@x", "k", List.of("2", "1"), "tok_ok");

        assertThat(one.fingerprint()).isEqualTo(other.fingerprint());
    }

    @Test
    void anotherBookOrAnotherTokenIsAnotherRequest() {
        PurchaseCommand one = new PurchaseCommand("a@x", "k", List.of("1", "2"), "tok_ok");

        assertThat(one.fingerprint())
                .isNotEqualTo(new PurchaseCommand("a@x", "k", List.of("1", "3"), "tok_ok").fingerprint())
                .isNotEqualTo(new PurchaseCommand("a@x", "k", List.of("1", "2"), "tok_reject").fingerprint());
    }

    @Test
    void malformedRequestsAreRefused() {
        assertThatThrownBy(() -> new PurchaseCommand("a@x", " ", List.of("1"), "t"))
                .isInstanceOf(InvalidPurchaseException.class);
        assertThatThrownBy(() -> new PurchaseCommand("a@x", "k".repeat(101), List.of("1"), "t"))
                .isInstanceOf(InvalidPurchaseException.class);
        assertThatThrownBy(() -> new PurchaseCommand("a@x", "k", List.of(), "t"))
                .isInstanceOf(InvalidPurchaseException.class);
        assertThatThrownBy(() -> new PurchaseCommand("a@x", "k", List.of("1", "1"), "t"))
                .isInstanceOf(InvalidPurchaseException.class);
    }
}
