package cat.tecnocampus.library.domain;

/**
 * {@code ACTIVE} is the only state that can change. A reservation leaves it exactly once: to
 * {@code COLLECTED} (the member took the copies), {@code CANCELLED} (the member gave them up) or
 * {@code EXPIRED} (the deadline passed and the sweep stored it).
 */
public enum ReservationStatus {
    ACTIVE, CANCELLED, COLLECTED, EXPIRED
}
