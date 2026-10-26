package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.Objects;
import java.util.UUID;

@Entity
public class Branch {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String city;

    protected Branch() {
    }

    public Branch(String name, String city) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name);
        this.city = Objects.requireNonNull(city);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }
}
