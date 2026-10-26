package cat.tecnocampus.library.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Names the member and the copies by business key (email, branch and barcodes), not by id: the
 * example's ids are random and change at every start, and the demo sends these from scenarios.http.
 */
public record ReservationRequest(@NotBlank String memberEmail, @NotBlank String branch,
                                 @NotEmpty List<@NotBlank String> barcodes) {
}
