package stats;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Map-backed counter used by unit tests so Mongo isn't required. */
public class InMemoryPostcodeStats implements PostcodeStats {

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    @Override
    public long incrementSearch(String postcode) {
        if (postcode == null || postcode.isBlank()) return 0;
        return counts.computeIfAbsent(postcode, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public long getSearchCount(String postcode) {
        if (postcode == null) return 0;
        AtomicLong v = counts.get(postcode);
        return v == null ? 0 : v.get();
    }
}
