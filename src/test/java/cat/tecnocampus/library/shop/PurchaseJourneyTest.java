package cat.tecnocampus.library.shop;

import cat.tecnocampus.library.GatewayContainerConfiguration;
import cat.tecnocampus.library.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static cat.tecnocampus.library.shop.ShopDemo.isbn;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole journey through the HTTP API, with the real database and the real gateway: buy, read
 * the purchase, find the receipt and the message; plus the answers a client must be able to tell
 * apart.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GatewayContainerConfiguration.class})
class PurchaseJourneyTest {

    private static final String ANNA = "member01@library.test";

    @Autowired MockMvcTester mvc;
    @Autowired ShopDemo demo;

    @BeforeEach
    void freshShop() {
        demo.reset();
    }

    private static String body(String token, String... isbns) {
        return """
                {"memberEmail": "%s", "isbns": ["%s"], "paymentToken": "%s"}
                """.formatted(ANNA, String.join("\", \"", isbns), token);
    }

    private MvcTestResult buy(String key, String body) {
        return mvc.post().uri("/better/purchases").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    @Test
    void theMemberBuysReadsThePurchaseAndReceivesTheReceipt() throws Exception {
        // When: buy two books, one of them sold out
        MvcTestResult bought = buy("journey-1", body("tok_ok", isbn(1), ShopDemo.SOLD_OUT_ISBN));

        // Then
        assertThat(bought).hasStatus(HttpStatus.CREATED).headers().containsHeader("Location");
        assertThat(bought).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        assertThat(bought).bodyJson().extractingPath("$.skippedIsbns[0]").isEqualTo(ShopDemo.SOLD_OUT_ISBN);
        String location = bought.getResponse().getHeader("Location");
        String receipt = bought.getResponse().getContentAsString().replaceAll(".*\"number\":\"(R-[0-9A-F]+)\".*", "$1");

        // and the purchase can be read back with its receipt
        assertThat(mvc.get().uri(location)).hasStatusOk()
                .bodyJson().extractingPath("$.receipt.number").isEqualTo(receipt);
        // and the member received one message that names it
        assertThat(demo.mailbox().sentTo(ANNA)).singleElement()
                .satisfies(mail -> assertThat(mail.text()).contains(receipt, "Title 001"));
    }

    @Test
    void aRetryGetsTheSameStatusAndBody() throws Exception {
        MvcTestResult first = buy("journey-2", body("tok_ok", isbn(2)));
        MvcTestResult retry = buy("journey-2", body("tok_ok", isbn(2)));

        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(retry.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
    }

    @Test
    void theClientCanTellEveryAnswerApart() {
        assertThat(mvc.post().uri("/better/purchases").contentType(MediaType.APPLICATION_JSON)
                .content(body("tok_ok", isbn(3)))).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(buy("j-404", body("tok_ok", "978-00-000000"))).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(buy("j-409", body("tok_ok", ShopDemo.SOLD_OUT_ISBN))).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.type").isEqualTo("/problems/nothing-available");
        assertThat(buy("j-402", body("tok_reject", isbn(3)))).hasStatus(HttpStatus.PAYMENT_REQUIRED)
                .bodyJson().extractingPath("$.status").isEqualTo("DECLINED");
        assertThat(buy("j-202", body("tok_pending", isbn(4)))).hasStatus(HttpStatus.ACCEPTED)
                .bodyJson().extractingPath("$.status").isEqualTo("RECONCILE");
        assertThat(buy("j-402", body("tok_reject", isbn(5)))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson().extractingPath("$.type").isEqualTo("/problems/idempotency-key-reused");
    }
}
