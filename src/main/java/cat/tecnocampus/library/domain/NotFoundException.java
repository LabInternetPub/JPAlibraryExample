package cat.tecnocampus.library.domain;

/** Something the request names does not exist. Answered as 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object key) {
        super(what + " not found: " + key);
    }
}
