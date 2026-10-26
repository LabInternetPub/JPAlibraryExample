package cat.tecnocampus.library.shelfmap;

import cat.tecnocampus.library.TestcontainersConfiguration;
import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.ReservationRepository;
import cat.tecnocampus.library.scenario.Measured;
import cat.tecnocampus.library.scenario.Measurement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ShelfMapTest {

    @Autowired NaiveShelfMapService naive;
    @Autowired ProjectedShelfMapService projected;
    @Autowired BranchRepository branches;
    @Autowired CopyRepository copies;
    @Autowired ReservationRepository reservations;
    @Autowired Measurement measurement;
    @Autowired MockMvcTester mvc;

    @Test
    void bothVersionsShowTheSameStatusForEveryCopy() {
        // When
        Map<UUID, CopyStatus> naiveStatuses = naive.get("Central").copies().stream()
                .collect(Collectors.toMap(CopyItem::id, CopyItem::status));
        Map<UUID, CopyStatus> compactStatuses = projected.get("Central").statusesByCopy();

        // Then
        assertThat(compactStatuses).hasSize(20_000).isEqualTo(naiveStatuses);
    }

    @Test
    void anExpiredReservationIsShownAsAvailableWithoutAnyCleanup() {
        // Given: 100 reservations expired yesterday, 400 expire in 47 hours
        Map<CopyStatus, Long> today = count(projected.get("Central").statusesByCopy());

        // When: the same data read two days later, with nothing cleaned up
        Clock inTwoDays = Clock.offset(Clock.systemUTC(), Duration.ofDays(2));
        Map<CopyStatus, Long> later = count(new ProjectedShelfMapService(branches, copies, inTwoDays)
                .get("Central").statusesByCopy());
        Map<CopyStatus, Long> laterNaive = count(new NaiveShelfMapService(branches, copies, inTwoDays)
                .get("Central").copies().stream().collect(Collectors.toMap(CopyItem::id, CopyItem::status)));

        // Then
        assertThat(today).containsEntry(CopyStatus.RESERVED, 400L).containsEntry(CopyStatus.AVAILABLE, 18_550L);
        assertThat(later).doesNotContainKey(CopyStatus.RESERVED).containsEntry(CopyStatus.AVAILABLE, 18_950L);
        assertThat(laterNaive).isEqualTo(later);
    }

    @Test
    void theNaiveVersionBuildsTwentyThousandEntitiesAndTheProjectionNone() {
        // When
        Measured<?> naiveCall = measurement.sql(() -> naive.get("Central"));
        Measured<?> projectedCall = measurement.sql(() -> projected.get("Central"));

        // Then: both send two statements; only the branch is an entity in the projection
        assertThat(naiveCall.sql().jdbcStatements()).isEqualTo(2);
        assertThat(naiveCall.sql().entityLoads()).isEqualTo(20_000 + 300 + 1);
        assertThat(projectedCall.sql().jdbcStatements()).isEqualTo(2);
        assertThat(projectedCall.sql().entityLoads()).isEqualTo(1);
    }

    @Test
    void theCompactFormatHasOneEntryPerShelfWithArraysOfTheSameLength() {
        // When
        ShelfMapResponse map = projected.get("Central");

        // Then: 5 floors x 40 shelves x 100 copies
        assertThat(map.shelves()).hasSize(200).allSatisfy(shelf -> {
            assertThat(shelf.copyIds()).hasSize(100);
            assertThat(shelf.barcodes()).hasSize(100);
            assertThat(shelf.isbns()).hasSize(100);
            assertThat(shelf.statuses()).hasSize(100);
        });
    }

    @Test
    void neitherResponseRevealsWhoHoldsACopyNorUntilWhen() throws Exception {
        for (String uri : new String[] {"/naive/branches/Central/shelf-map", "/better/branches/Central/shelf-map"}) {
            // When
            String body = mvc.get().uri(uri).exchange().getResponse().getContentAsString();

            // Then
            assertThat(body).doesNotContain("reservationId", "reservedUntil", "member");
            assertThat(reservations.findAllIds()).allSatisfy(id -> assertThat(body).doesNotContain(id.toString()));
        }
    }

    @Test
    void anUnknownBranchIsNotFound() {
        assertThat(mvc.get().uri("/better/branches/Nowhere/shelf-map"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private static Map<CopyStatus, Long> count(Map<UUID, CopyStatus> statuses) {
        return statuses.values().stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
