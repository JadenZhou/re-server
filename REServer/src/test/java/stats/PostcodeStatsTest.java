package stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises InMemoryPostcodeStats — same contract MongoPostcodeStats implements
 * (atomic increment + read-back). The Mongo impl is structurally identical and
 * uses Mongo's $inc to provide the same atomicity guarantees in production.
 */
class PostcodeStatsTest {

    @Test
    void unsearchedPostcodeReadsZero() {
        PostcodeStats stats = new InMemoryPostcodeStats();
        assertEquals(0, stats.getSearchCount("2000"));
    }

    @Test
    void incrementReturnsNewTotalAndPersists() {
        PostcodeStats stats = new InMemoryPostcodeStats();
        assertEquals(1, stats.incrementSearch("2000"));
        assertEquals(2, stats.incrementSearch("2000"));
        assertEquals(3, stats.incrementSearch("2000"));
        assertEquals(3, stats.getSearchCount("2000"));
    }

    @Test
    void countersForDifferentPostcodesAreIndependent() {
        PostcodeStats stats = new InMemoryPostcodeStats();
        stats.incrementSearch("2000");
        stats.incrementSearch("2000");
        stats.incrementSearch("2480");
        assertEquals(2, stats.getSearchCount("2000"));
        assertEquals(1, stats.getSearchCount("2480"));
        assertEquals(0, stats.getSearchCount("2770"));
    }

    @Test
    void nullAndBlankPostcodesAreIgnored() {
        PostcodeStats stats = new InMemoryPostcodeStats();
        assertEquals(0, stats.incrementSearch(null));
        assertEquals(0, stats.incrementSearch(""));
        assertEquals(0, stats.incrementSearch("   "));
        assertEquals(0, stats.getSearchCount(null));
    }

    @Test
    void concurrentIncrementsCountAllOperations() throws InterruptedException {
        PostcodeStats stats = new InMemoryPostcodeStats();
        int threads = 8;
        int incrementsPerThread = 1000;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) stats.incrementSearch("2000");
            });
            workers[i].start();
        }
        for (Thread t : workers) t.join();
        assertEquals((long) threads * incrementsPerThread, stats.getSearchCount("2000"));
    }
}
