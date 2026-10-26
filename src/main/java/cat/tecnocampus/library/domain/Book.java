package cat.tecnocampus.library.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
public class Book {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String isbn;

    @Column(nullable = false)
    private String title;

    private int publicationYear;

    // LAZY written explicitly: JPA's default for @ManyToOne is EAGER, which would load the author
    // every time a book is loaded, whether the code needs it or not.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Author author;

    // Collections are LAZY by default: they are loaded the first time the code touches them.
    @ManyToMany
    @JoinTable(name = "book_subject",
            joinColumns = @JoinColumn(name = "book_id"),
            inverseJoinColumns = @JoinColumn(name = "subject_id"))
    private List<Subject> subjects = new ArrayList<>();

    protected Book() {
    }

    public Book(String isbn, String title, int publicationYear, Author author) {
        this.id = UUID.randomUUID();
        this.isbn = Objects.requireNonNull(isbn);
        this.title = Objects.requireNonNull(title);
        this.publicationYear = publicationYear;
        this.author = Objects.requireNonNull(author);
    }

    public void addSubject(Subject subject) {
        if (!subjects.contains(subject)) {
            subjects.add(subject);
        }
    }

    public UUID getId() {
        return id;
    }

    public String getIsbn() {
        return isbn;
    }

    public String getTitle() {
        return title;
    }

    public int getPublicationYear() {
        return publicationYear;
    }

    public Author getAuthor() {
        return author;
    }

    public List<Subject> getSubjects() {
        return Collections.unmodifiableList(subjects);
    }
}
