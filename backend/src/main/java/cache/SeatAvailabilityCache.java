package cache;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory cache for seat availability per show.
 * Entries expire lazily after five seconds.
 */
public class SeatAvailabilityCache {
     private static final long TTL_MILLIS = 5_000L;

     private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * Retrieve cached seat availability if valid and not expired.
     * Returns null if cache miss or expired.
     */
    public Map<String, String> get(Long showId) {
        CacheEntry entry = cache.get(showId);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(showId, entry);
            return null;
        }
        return entry.occupiedSeats;
    }

    /**
     * Store or update cached seat availability.
     */
    public Map<String, String> put(Long showId, Map<String, String> occupiedSeats) {
        CacheEntry entry = new CacheEntry(occupiedSeats);
        cache.put(showId, entry);
        return entry.occupiedSeats;
    }

    /**
     * Force invalidate cache entry for a show (e.g., after expiry job).
     */
    public void invalidate(Long showId) {
        cache.remove(showId);
    }

    public void updateSeat(Long showId, String seatKey, String status) {
        CacheEntry entry = liveEntry(showId);
        if (entry != null) {
            entry.occupiedSeats.put(seatKey, status);
        }
    }

    /**
     * Remove a single seat from cache (surgical update on cancellation/expiry).
     */
    public void removeSeat(Long showId, String seatKey) {
        CacheEntry entry = liveEntry(showId);
        if (entry != null) {
            entry.occupiedSeats.remove(seatKey);
        }
    }

    private CacheEntry liveEntry(Long showId) {
        CacheEntry entry = cache.get(showId);
        if (entry != null && !entry.isExpired()) {
            return entry;
        }
        if (entry != null) {
            cache.remove(showId, entry);
        }
        return null;
    }

    /**
     * Get cache size (for monitoring).
     */
    public int getCacheSize() {
        return cache.size();
    }

    private static final class CacheEntry {
        private final Map<String, String> occupiedSeats;
        private final long timestamp;

        private CacheEntry(Map<String, String> occupiedSeats) {
            this.occupiedSeats = new ConcurrentHashMap<>(new HashMap<>(occupiedSeats));
            this.timestamp = System.currentTimeMillis();
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - timestamp > TTL_MILLIS;
        }
    }
}
