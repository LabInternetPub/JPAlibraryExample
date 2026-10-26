package cat.tecnocampus.library.shelfmap;

import cat.tecnocampus.library.domain.CopyStatus;
import cat.tecnocampus.library.persistence.ShelfRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The compact response: copies grouped by floor and shelf. Floor and shelf are written once per
 * shelf, and each copy's values are at the same position of the shelf's arrays: copy i of a shelf
 * is {@code copyIds[i]}, {@code barcodes[i]}, {@code isbns[i]} and {@code statuses.charAt(i)}.
 */
public record ShelfMapResponse(String branch, Map<String, String> legend, List<Shelf> shelves) {

    public static final Map<String, String> LEGEND = buildLegend();

    public record Shelf(int floor, String shelf, List<UUID> copyIds, List<String> barcodes, List<String> isbns,
                        String statuses) {
    }

    /** Rows must arrive ordered by floor and shelf, as the query returns them. */
    static ShelfMapResponse from(String branch, List<ShelfRow> rows) {
        Map<String, ShelfBuilder> shelves = new LinkedHashMap<>();
        for (ShelfRow row : rows) {
            shelves.computeIfAbsent(row.floor() + "/" + row.shelf(), key -> new ShelfBuilder(row)).add(row);
        }
        return new ShelfMapResponse(branch, LEGEND, shelves.values().stream().map(ShelfBuilder::build).toList());
    }

    /** How a client reads the format back: each copy id with its status. */
    public Map<UUID, CopyStatus> statusesByCopy() {
        Map<UUID, CopyStatus> statuses = new LinkedHashMap<>();
        for (Shelf shelf : shelves) {
            for (int i = 0; i < shelf.copyIds().size(); i++) {
                statuses.put(shelf.copyIds().get(i), CopyStatus.valueOf(LEGEND.get(String.valueOf(shelf.statuses().charAt(i)))));
            }
        }
        return statuses;
    }

    static char code(CopyStatus status) {
        return switch (status) {
            case AVAILABLE -> 'A';
            case ON_LOAN -> 'L';
            case RESERVED -> 'R';
            case WITHDRAWN -> 'W';
        };
    }

    private static Map<String, String> buildLegend() {
        Map<String, String> legend = new LinkedHashMap<>();
        for (CopyStatus status : CopyStatus.values()) {
            legend.put(String.valueOf(code(status)), status.name());
        }
        return Map.copyOf(legend);
    }

    private static final class ShelfBuilder {

        private final int floor;
        private final String shelf;
        private final List<UUID> copyIds = new ArrayList<>();
        private final List<String> barcodes = new ArrayList<>();
        private final List<String> isbns = new ArrayList<>();
        private final StringBuilder statuses = new StringBuilder();

        ShelfBuilder(ShelfRow first) {
            this.floor = first.floor();
            this.shelf = first.shelf();
        }

        void add(ShelfRow row) {
            copyIds.add(row.copyId());
            barcodes.add(row.barcode());
            isbns.add(row.isbn());
            statuses.append(code(row.status()));
        }

        Shelf build() {
            return new Shelf(floor, shelf, List.copyOf(copyIds), List.copyOf(barcodes), List.copyOf(isbns),
                    statuses.toString());
        }
    }
}
