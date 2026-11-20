package cat.tecnocampus.library.web;

import cat.tecnocampus.library.domain.CopiesUnavailableException;
import cat.tecnocampus.library.domain.MemberLimitExceededException;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.domain.ReservationExpiredException;
import cat.tecnocampus.library.domain.ReservationNotActiveException;
import cat.tecnocampus.library.domain.ShopExceptions.IdempotencyKeyReusedException;
import cat.tecnocampus.library.domain.ShopExceptions.InvalidPurchaseException;
import cat.tecnocampus.library.domain.ShopExceptions.NothingAvailableException;
import cat.tecnocampus.library.domain.ShopExceptions.PurchaseInProgressException;
import cat.tecnocampus.library.importing.InvalidImportFileException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns exceptions into application/problem+json responses. A rejection is a clean 409 with its
 * own type, so a client can tell "copy taken" from "limit reached" from a server error.
 */
@RestControllerAdvice
public class ProblemDetailsHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidImportFileException.class)
    ProblemDetail invalidImportFile(InvalidImportFileException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("line", e.getLine());
        return problem;
    }

    @ExceptionHandler(CopiesUnavailableException.class)
    ProblemDetail copiesUnavailable(CopiesUnavailableException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "copies-unavailable", "Copies not available",
                "Some of the requested copies are not available; nothing was reserved");
        problem.setProperty("unavailableCopies", e.getCopyIds());
        return problem;
    }

    @ExceptionHandler(MemberLimitExceededException.class)
    ProblemDetail memberLimitExceeded(MemberLimitExceededException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "member-limit-exceeded", "Member limit exceeded",
                e.getMessage());
        problem.setProperty("activeItems", e.getExisting());
        problem.setProperty("requested", e.getRequested());
        return problem;
    }

    @ExceptionHandler(ReservationNotActiveException.class)
    ProblemDetail reservationNotActive(ReservationNotActiveException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "reservation-not-active", "Reservation not active",
                "The reservation has expired, or was already collected or cancelled");
        problem.setProperty("reservationId", e.getReservationId());
        return problem;
    }

    @ExceptionHandler(ReservationExpiredException.class)
    ProblemDetail reservationExpired(ReservationExpiredException e) {
        ProblemDetail problem = problem(HttpStatus.GONE, "reservation-expired", "Reservation expired",
                "The reservation's deadline has passed");
        problem.setProperty("reservationId", e.getReservationId());
        return problem;
    }

    /** Waited longer than the lock timeout: not a business rejection, the request can be retried. */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail lockTimeout(PessimisticLockingFailureException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "lock-timeout", "Busy, try again",
                "The copies are being reserved by another request");
    }

    // ---- Selling books ------------------------------------------------------------------------

    @ExceptionHandler(InvalidPurchaseException.class)
    ProblemDetail invalidPurchase(InvalidPurchaseException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-purchase", "Invalid purchase", e.getMessage());
    }

    @ExceptionHandler(NothingAvailableException.class)
    ProblemDetail nothingAvailable(NothingAvailableException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "nothing-available", "Nothing available",
                "None of the requested books is in stock; nothing was bought or charged");
        problem.setProperty("isbns", e.getIsbns());
        return problem;
    }

    /** The same key with a different body: the client reused a key, this is not a retry. */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", "Idempotency key reused",
                e.getMessage());
    }

    /** Another request with the same key is still charging: retry with the same key. */
    @ExceptionHandler(PurchaseInProgressException.class)
    ResponseEntity<ProblemDetail> purchaseInProgress(PurchaseInProgressException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "purchase-in-progress", "Purchase in progress",
                        e.getMessage()));
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + type));
        problem.setTitle(title);
        return problem;
    }
}
