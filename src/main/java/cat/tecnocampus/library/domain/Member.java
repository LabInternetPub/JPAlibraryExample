package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Objects;
import java.util.UUID;

/**
 * A member of the library.
 *
 * <p>Besides identifying who borrows and reserves, the member row is the one row every request
 * of the same member shares. Locking it is what turns the per-member limit (a rule over a set of
 * rows) into a conflict on a single row.
 */
@Entity
public class Member {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String email;

    protected Member() {
    }

    public Member(String name, String email) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name);
        this.email = Objects.requireNonNull(email);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }
}
