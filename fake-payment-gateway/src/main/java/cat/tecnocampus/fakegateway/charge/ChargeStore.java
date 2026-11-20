package cat.tecnocampus.fakegateway.charge;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * In-memory record of everything charged. It exists so a test can ask the question the
 * idempotency case actually turns on: "how many charges did you really take?"
 */
@Component
public class ChargeStore {

    /** Whether the charge was taken now, or replayed from a previous request with the same key. */
    public record Result(Charge charge, boolean replay) {
    }

    private final ConcurrentMap<String, Charge> byProviderRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> providerRefByIdempotencyKey = new ConcurrentHashMap<>();

    /**
     * Creates the charge, unless this idempotency key has already produced one — in which case
     * the original comes back untouched. Ten concurrent calls with the same key create exactly
     * one charge, which is the property the acceptance suite checks.
     */
    public Result createOrReplay(String idempotencyKey, Supplier<Charge> factory) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            Charge charge = factory.get();
            byProviderRef.put(charge.providerRef(), charge);
            return new Result(charge, false);
        }

        boolean[] created = {false};
        String providerRef = providerRefByIdempotencyKey.computeIfAbsent(idempotencyKey, key -> {
            Charge charge = factory.get();
            byProviderRef.put(charge.providerRef(), charge);
            created[0] = true;
            return charge.providerRef();
        });

        return new Result(byProviderRef.get(providerRef), !created[0]);
    }

    public Optional<Charge> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providerRefByIdempotencyKey.get(idempotencyKey))
                .map(byProviderRef::get);
    }

    public List<Charge> all() {
        return List.copyOf(byProviderRef.values());
    }

    public void clear() {
        byProviderRef.clear();
        providerRefByIdempotencyKey.clear();
    }
}
