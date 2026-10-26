package cat.tecnocampus.library.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A member's reservation of one or more copies, all or nothing, for 48 hours.
 *
 * <p>It refers to its copies by id: a reservation does not own the copies, and most
 * reservations end without anyone collecting the copies.
 */
@Entity
public class Reservation {

    public static final Duration DURATION = Duration.ofHours(48);

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Member member;

    @ElementCollection
    @CollectionTable(name = "reservation_copy", joinColumns = @JoinColumn(name = "reservation_id"))
    @Column(name = "copy_id", nullable = false)
    private List<UUID> copyIds = new ArrayList<>();

    private int quantity;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    protected Reservation() {
    }

    public Reservation(Member member, List<UUID> copyIds, Instant now) {
        if (copyIds.isEmpty()) {
            throw new IllegalArgumentException("A reservation needs at least one copy");
        }
        if (new HashSet<>(copyIds).size() != copyIds.size()) {
            throw new IllegalArgumentException("A copy cannot be requested twice");
        }
        this.id = UUID.randomUUID();
        this.member = Objects.requireNonNull(member);
        this.copyIds = new ArrayList<>(copyIds);
        this.quantity = copyIds.size();
        this.createdAt = now;
        this.expiresAt = now.plus(DURATION);
        this.status = ReservationStatus.ACTIVE;
    }

    /**
     * All or nothing: every requested copy must be among the copies read and available now.
     * A requested copy that was not found counts as unavailable.
     *
     * <p>The answer is only as reliable as the read that produced {@code found}: an unlocked read
     * may already be out of date when the reservation is written.
     */
    public static void checkAvailable(Collection<UUID> requested, Collection<Copy> found, Instant now) {
        Map<UUID, Copy> byId = found.stream().collect(Collectors.toMap(Copy::getId, Function.identity()));
        List<UUID> unavailable = requested.stream()
                .filter(id -> !byId.containsKey(id) || !byId.get(id).isAvailableAt(now))
                .toList();
        if (!unavailable.isEmpty()) {
            throw new CopiesUnavailableException(unavailable);
        }
    }

    /** Marks each requested copy as held by this reservation until it expires. */
    public void reserve(Collection<Copy> copies, Instant now) {
        if (copies.size() != quantity
                || !copies.stream().map(Copy::getId).collect(Collectors.toSet()).containsAll(copyIds)) {
            throw new IllegalArgumentException("The copies do not match the reservation");
        }
        copies.forEach(copy -> copy.reserve(id, expiresAt, now));
    }

    /** Live reservations count towards the member's limit; expired ones no longer do. */
    public boolean isLiveAt(Instant now) {
        return status == ReservationStatus.ACTIVE && now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public List<UUID> getCopyIds() {
        return List.copyOf(copyIds);
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ReservationStatus getStatus() {
        return status;
    }
}
