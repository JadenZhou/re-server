package stats;

/**
 * Tracks how often each postcode has been searched. Two impls:
 *   MongoPostcodeStats   — persists in the `postcode_stats` collection
 *   InMemoryPostcodeStats — for unit tests (no Mongo)
 */
public interface PostcodeStats {

    /** Atomically bumps the counter for `postcode` and returns the new total. */
    long incrementSearch(String postcode);

    /** Returns the count for `postcode`, or 0 if never searched. */
    long getSearchCount(String postcode);
}
