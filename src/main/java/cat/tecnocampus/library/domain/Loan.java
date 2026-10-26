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

    protected Loan() {
    }

    public Loan(Member member, Copy copy, Instant now) {
        copy.lend(now);
        this.id = UUID.randomUUID();
        this.member = Objects.requireNonNull(member);
        this.copy = copy;
        this.startedAt = now;
        this.dueAt = now.plus(DURATION);
    }

    public void giveBack() {
        if (returned) {
            throw new IllegalStateException("The loan is already closed");
        }
        copy.returnToShelf();
        returned = true;
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
}
