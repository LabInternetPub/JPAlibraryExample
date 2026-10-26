package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Objects;
import java.util.UUID;

@Entity
public class Author {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String nationality;

    protected Author() {
    }

    public Author(String name, String nationality) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name);
        this.nationality = nationality;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getNationality() {
        return nationality;
    }
}
