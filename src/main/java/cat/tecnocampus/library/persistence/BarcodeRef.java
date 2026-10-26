package cat.tecnocampus.library.persistence;

import java.util.UUID;

/** Scalar projection: a copy's barcode and id, without a managed Copy entity. */
public record BarcodeRef(String barcode, UUID id) {
}
