package shopify.graphql

/**
 * DAR-BE-320. How many calls one extract made to Shopify, so a run can show what it cost and a change that
 * reads more than it needs is visible: graphql (every POST to graphql.json), costPoints (Shopify's
 * requestedQueryCost, summed), throttled, bulkStarts, bulkPolls, downloads.
 *
 * <p>Per thread: an extract runs on one thread from start to finish (each side of a run on its own), and
 * {@link #start} resets it, so a pooled thread never carries an earlier extract's counts.</p>
 */
class ShopifyCallTally {

    private static final ThreadLocal<Map<String, Long>> CURRENT = new ThreadLocal<>()

    /** Begin counting for this thread's extract, discarding anything left from an earlier one. */
    static void start() {
        CURRENT.set(new LinkedHashMap<String, Long>())
    }

    /** Adds n to key; a no-op when no extract on this thread started counting. */
    static void add(String key, long n = 1L) {
        Map<String, Long> tally = CURRENT.get()
        if (tally == null || n == 0L) return
        tally.put(key, (tally.get(key) ?: 0L) + n)
    }

    /** The counts so far, or null when nothing was called (so callers can leave the key out). */
    static Map<String, Long> snapshot() {
        Map<String, Long> tally = CURRENT.get()
        return tally ? new LinkedHashMap<String, Long>(tally) : null
    }

    /** The counts so far (as {@link #snapshot}), and stop counting on this thread. */
    static Map<String, Long> stop() {
        Map<String, Long> out = snapshot()
        CURRENT.remove()
        return out
    }
}
