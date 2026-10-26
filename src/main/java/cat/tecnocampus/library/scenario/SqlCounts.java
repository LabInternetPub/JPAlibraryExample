package cat.tecnocampus.library.scenario;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What Hibernate did while an action ran.
 *
 * @param jdbcStatements    single statements sent to the database (one round trip each)
 * @param jdbcBatches       JDBC batches sent; one batch carries many INSERTs or UPDATEs in one round trip
 * @param entityLoads       entities built from database rows (managed by the persistence context)
 * @param entityInserts     entities inserted
 * @param entityUpdates     entities updated by dirty checking
 * @param collectionFetches lazy collections initialized
 */
public record SqlCounts(long jdbcStatements, long jdbcBatches, long entityLoads, long entityInserts,
                        long entityUpdates, long collectionFetches) {

    /** Round trips to the database: every statement and every batch is one. */
    public long roundTrips() {
        return jdbcStatements + jdbcBatches;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("JDBC statements", jdbcStatements);
        map.put("JDBC batches", jdbcBatches);
        map.put("entities loaded", entityLoads);
        map.put("entities inserted", entityInserts);
        map.put("entities updated", entityUpdates);
        map.put("collections fetched", collectionFetches);
        return map;
    }
}
