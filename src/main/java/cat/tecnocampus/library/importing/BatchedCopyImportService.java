package cat.tecnocampus.library.importing;

import cat.tecnocampus.library.domain.Book;
import cat.tecnocampus.library.domain.Branch;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.persistence.BarcodeRef;
import cat.tecnocampus.library.persistence.BookRepository;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import cat.tecnocampus.library.persistence.IsbnRef;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Better, with JPA only. Each cause of the naive version removed with a JPA tool:
 * <ul>
 *   <li>one query for every ISBN and one for every barcode of the branch, as scalars, instead
 *       of two lookups per row;</li>
 *   <li>{@code persist()} for new copies: no merge, no SELECT by id;</li>
 *   <li>no query between the persists of a chunk, so auto-flush never fires and the inserts of a
 *       chunk leave together as JDBC batches;</li>
 *   <li>{@code flush()} + {@code clear()} every chunk: at most one chunk of copies is managed, and
 *       each flush dirty-checks only that chunk.</li>
 * </ul>
 * Everything still runs in one transaction: the chunks bound memory, they do not commit.
 */
@Service
public class BatchedCopyImportService {

    static final int CHUNK = 500;

    private final CopyCsvParser parser;
    private final BranchRepository branches;
    private final BookRepository books;
    private final CopyRepository copies;
    private final EntityManager entityManager;

    public BatchedCopyImportService(CopyCsvParser parser, BranchRepository branches, BookRepository books,
                                    CopyRepository copies, EntityManager entityManager) {
        this.parser = parser;
        this.branches = branches;
        this.books = books;
        this.copies = copies;
        this.entityManager = entityManager;
    }

    @Transactional
    public ImportResult importCopies(String branchName, InputStream csv) {
        UUID branchId = branches.findByName(branchName)
                .orElseThrow(() -> new NotFoundException("Branch", branchName)).getId();
        List<CopyRow> rows = parser.parse(csv);

        Map<String, UUID> bookIds = books.findIsbnIndex().stream()
                .collect(Collectors.toMap(IsbnRef::isbn, IsbnRef::id));
        // Every ISBN checked before writing anything: a bad last row costs no writes at all.
        rows.stream().filter(row -> !bookIds.containsKey(row.isbn())).findFirst().ifPresent(row -> {
            throw new InvalidImportFileException(row.line(), "unknown ISBN " + row.isbn());
        });
        Map<String, UUID> existing = copies.findBarcodeIndex(branchId).stream()
                .collect(Collectors.toMap(BarcodeRef::barcode, BarcodeRef::id));

        int created = 0;
        int updated = 0;
        for (int start = 0; start < rows.size(); start += CHUNK) {
            List<CopyRow> chunk = rows.subList(start, Math.min(start + CHUNK, rows.size()));
            // A reference is a proxy holding only the id: no SELECT of the branch or the books.
            Branch branch = entityManager.getReference(Branch.class, branchId);
            Map<UUID, Copy> loaded = loadExisting(chunk, existing);
            for (CopyRow row : chunk) {
                UUID id = existing.get(row.barcode());
                if (id == null) {
                    Book book = entityManager.getReference(Book.class, bookIds.get(row.isbn()));
                    entityManager.persist(new Copy(branch, book, row.barcode(), row.floor(), row.shelf(), row.condition()));
                    created++;
                } else if (loaded.get(id).reimport(row.floor(), row.shelf(), row.condition())) {
                    updated++;   // dirty checking writes the UPDATE at the flush below
                }
            }
            entityManager.flush();
            entityManager.clear();
        }
        return new ImportResult(branchName, rows.size(), created, updated, rows.size() - created - updated);
    }

    /** The existing copies of one chunk in one query; a chunk of new copies needs none. */
    private Map<UUID, Copy> loadExisting(List<CopyRow> chunk, Map<String, UUID> existing) {
        List<UUID> ids = chunk.stream().map(row -> existing.get(row.barcode())).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return copies.findAllById(ids).stream().collect(Collectors.toMap(Copy::getId, Function.identity()));
    }
}
