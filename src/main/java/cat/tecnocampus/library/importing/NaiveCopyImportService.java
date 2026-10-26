package cat.tecnocampus.library.importing;

import cat.tecnocampus.library.domain.Book;
import cat.tecnocampus.library.domain.Branch;
import cat.tecnocampus.library.domain.Copy;
import cat.tecnocampus.library.domain.NotFoundException;
import cat.tecnocampus.library.persistence.BookRepository;
import cat.tecnocampus.library.persistence.BranchRepository;
import cat.tecnocampus.library.persistence.CopyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * The obvious version: for each row, look the book and the copy up, create or update the copy,
 * save it. Correct, idempotent (the lookup finds the existing copy) and atomic (one transaction).
 *
 * <p>What goes wrong only shows at volume:
 * <ul>
 *   <li>two lookups per row: the write-side N+1;</li>
 *   <li>{@code save()} of a new copy is a {@code merge()}, because the id is assigned in the
 *       constructor: Spring Data cannot tell the copy is new, and merge first SELECTs it by id;</li>
 *   <li>the copy lookup reads the table where the previous insert is still pending, so flush mode
 *       AUTO flushes before every lookup: JDBC batching is configured but every batch carries a
 *       single insert, and every flush dirty-checks every copy managed so far (the cost grows as n²);</li>
 *   <li>the persistence context keeps all 20,000 copies until the transaction ends.</li>
 * </ul>
 */
@Service
public class NaiveCopyImportService {

    private final CopyCsvParser parser;
    private final BranchRepository branches;
    private final BookRepository books;
    private final CopyRepository copies;

    public NaiveCopyImportService(CopyCsvParser parser, BranchRepository branches, BookRepository books,
                                  CopyRepository copies) {
        this.parser = parser;
        this.branches = branches;
        this.books = books;
        this.copies = copies;
    }

    @Transactional
    public ImportResult importCopies(String branchName, InputStream csv) {
        Branch branch = branches.findByName(branchName).orElseThrow(() -> new NotFoundException("Branch", branchName));
        List<CopyRow> rows = parser.parse(csv);
        int created = 0;
        int updated = 0;
        for (CopyRow row : rows) {
            Book book = books.findByIsbn(row.isbn())
                    .orElseThrow(() -> new InvalidImportFileException(row.line(), "unknown ISBN " + row.isbn()));
            Optional<Copy> existing = copies.findByBranchIdAndBarcode(branch.getId(), row.barcode());
            if (existing.isPresent()) {
                if (existing.get().reimport(row.floor(), row.shelf(), row.condition())) {
                    updated++;
                }
                copies.save(existing.get());
            } else {
                copies.save(new Copy(branch, book, row.barcode(), row.floor(), row.shelf(), row.condition()));
                created++;
            }
        }
        return new ImportResult(branch.getName(), rows.size(), created, updated, rows.size() - created - updated);
    }
}
