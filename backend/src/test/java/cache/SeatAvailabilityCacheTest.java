package cache;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeatAvailabilityCacheTest {

    @Test
    void cachesEmptyOccupancyMapAsAHit() {
        SeatAvailabilityCache cache = new SeatAvailabilityCache();

        cache.put(1L, Map.of());

        assertTrue(cache.get(1L).isEmpty());
    }

    @Test
    void updatesAndRemovesSeatsOnlyForLiveEntries() {
        SeatAvailabilityCache cache = new SeatAvailabilityCache();
        cache.put(1L, Map.of("A-1", "HELD"));

        cache.updateSeat(1L, "B-2", "BOOKED");
        assertEquals(Map.of("A-1", "HELD", "B-2", "BOOKED"), cache.get(1L));

        cache.removeSeat(1L, "A-1");
        assertEquals(Map.of("B-2", "BOOKED"), cache.get(1L));
    }

    @Test
    void expiresEntriesLazily() throws InterruptedException {
        SeatAvailabilityCache cache = new SeatAvailabilityCache();
        cache.put(1L, Map.of("A-1", "BOOKED"));

        Thread.sleep(5_100L);

        assertNull(cache.get(1L));
        assertEquals(0, cache.getCacheSize());
    }
}
