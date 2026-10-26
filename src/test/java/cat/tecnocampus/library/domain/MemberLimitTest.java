package cat.tecnocampus.library.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemberLimitTest {

    @Test
    void aFirstRequestIsAccepted() {
        assertThatCode(() -> MemberLimit.check(0, 3)).doesNotThrowAnyException();
    }

    @Test
    void reachingExactlyTheLimitIsAccepted() {
        assertThatCode(() -> MemberLimit.check(2, 3)).doesNotThrowAnyException();
    }

    @Test
    void goingOverTheLimitIsRefused() {
        assertThatThrownBy(() -> MemberLimit.check(3, 3))
                .isInstanceOf(MemberLimitExceededException.class);
    }

    @Test
    void aRequestMustAskForSomething() {
        assertThatThrownBy(() -> MemberLimit.check(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
