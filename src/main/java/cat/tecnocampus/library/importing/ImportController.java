package cat.tecnocampus.library.importing;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** The same import, naive and batched. The file is uploaded as multipart field "file". */
@RestController
public class ImportController {

    private final NaiveCopyImportService naive;
    private final BatchedCopyImportService batched;

    public ImportController(NaiveCopyImportService naive, BatchedCopyImportService batched) {
        this.naive = naive;
        this.batched = batched;
    }

    @PostMapping("/naive/branches/{branch}/copies/import")
    public ImportResult naive(@PathVariable String branch, @RequestParam MultipartFile file) throws IOException {
        return naive.importCopies(branch, file.getInputStream());
    }

    @PostMapping("/better/branches/{branch}/copies/import")
    public ImportResult batched(@PathVariable String branch, @RequestParam MultipartFile file) throws IOException {
        return batched.importCopies(branch, file.getInputStream());
    }
}
