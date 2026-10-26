package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Objects;
import java.util.UUID;

@Entity
public class Subject {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    protected Subject() {
    }

    public Subject(String name) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
