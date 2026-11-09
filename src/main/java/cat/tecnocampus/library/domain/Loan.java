package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An open loan counts towards the member's limit until the copy is returned. */
@Entity
public class Loan {

    public static final Duration DURATION = Duration.ofDays(21);

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Copy copy;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(nullable = false)
    private Instant dueAt;

    private boolean returned;

    private Instant returnedAt;

    protected Loan() {
    }

    /** Lends an available copy over the counter. */
    public Loan(Member member, Copy copy, Instant now) {
        this(member, copy, now, true);
    }

    private Loan(Member member, Copy copy, Instant now, boolean lend) {
        if (lend) {
            copy.lend(now);
        }
        this.id = UUID.randomUUID();
        this.member = Objects.requireNonNull(member);
        this.copy = Objects.requireNonNull(copy);
        this.startedAt = now;
        this.dueAt = now.plus(DURATION);
    }

    /**
     * The loan of a copy collected from a reservation. The copy has already gone on loan, by
     * {@link Copy#collect(UUID, Instant)} or by a conditional update that checked who held it; the
     * loan only records it, so it does not call {@link Copy#lend(Instant)} again.
     */
    public static Loan forCollection(Member member, Copy copy, Instant now) {
        return new Loan(member, copy, now, false);
    }

    /**
     * Closes the loan and puts the copy back on the shelf. Idempotent: a retried return (the
     * return machine timed out and sent it again) is not an error and has no second effect.
     *
     * @return true only for the call that closed the loan
     */
    public boolean giveBack(Instant now) {
        if (returned) {
            return false;
        }
        copy.returnToShelf();
        returned = true;
        returnedAt = now;
        return true;
    }

    public boolean isOpen() {
        return !returned;
    }

    public UUID getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public Copy getCopy() {
        return copy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public boolean isReturned() {
        return returned;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }
}
