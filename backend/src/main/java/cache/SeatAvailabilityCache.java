package cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe in-memory cache for seat availability per show.
 * Stores seat occupancy with automatic TTL-based expiration and cleanup.
 * Supports surgical updates (add/remove individual seats) for efficiency.
 */
public class SeatAvailabilityCache {
    private static final Logger LOGGER = Logger.getLogger(SeatAvailabilityCache.class.getName());
    private static final long CLEANUP_INTERVAL_SECONDS = 5;
    private static final long HOLD_TTL_MILLIS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<Long, CachedShowSeats> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Long>> activeHolds = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupExecutor;

    /**
     * Retrieve cached seat availability if valid and not expired.
     * Returns null if cache miss or expired.
     */
    public CachedShowSeats get(Long showId) {
        CachedShowSeats cached = cache.get(showId);
        if (cached != null && !cached.isExpired()) {
            mergeActiveHolds(showId, cached);
            return cached;
        }
        // Lazily remove expired entries on access
        if (cached != null && cached.isExpired()) {
            cache.remove(showId);
        }
        return null;
    }

    /**
     * Store or update cached seat availability.
     */
    public CachedShowSeats put(Long showId, CachedShowSeats cachedSeats) {
        CachedShowSeats merged = cache.compute(showId, (id, existing) -> {
            if (existing != null && !existing.isExpired()) {
                existing.getOccupiedSeats().putAll(cachedSeats.getOccupiedSeats());
                return existing;
            }
            return cachedSeats;
        });
        mergeActiveHolds(showId, merged);
        return merged;
    }

    /**
     * Force invalidate cache entry for a show (e.g., after expiry job).
     */
    public void invalidate(Long showId) {
        cache.remove(showId);
    }

    /**
     * Add a single seat to cache (surgical update on booking confirmation).
     */
    public void addSeat(Long showId, String seatKey, CachedShowSeats.SeatStatus status) {
        if (status == CachedShowSeats.SeatStatus.HELD) {
            activeHolds.computeIfAbsent(showId, ignored -> new ConcurrentHashMap<>())
                    .put(seatKey, System.currentTimeMillis() + HOLD_TTL_MILLIS);
        } else {
            removeActiveHold(showId, seatKey);
        }
        CachedShowSeats cached = cache.get(showId);
        if (cached != null && !cached.isExpired()) {
            cached.addSeat(seatKey, status);
        }
    }

    /**
     * Remove a single seat from cache (surgical update on cancellation/expiry).
     */
    public void removeSeat(Long showId, String seatKey) {
        removeActiveHold(showId, seatKey);
        CachedShowSeats cached = cache.get(showId);
        if (cached != null && !cached.isExpired()) {
            cached.removeSeat(seatKey);
        }
    }

    /**
     * Start background cleanup scheduler to remove expired entries.
     */
    public void startCleanupScheduler() {
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "seat-cache-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredEntries,
            CLEANUP_INTERVAL_SECONDS,
            CLEANUP_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        LOGGER.info("Seat availability cache cleanup scheduler started");
    }

    /**
     * Stop the cleanup scheduler.
     */
    public void stopCleanupScheduler() {
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdownNow();
            LOGGER.info("Seat availability cache cleanup scheduler stopped");
        }
    }

    /**
     * Remove all expired cache entries.
     */
    private void cleanupExpiredEntries() {
        try {
            cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Cache cleanup failed", e);
        }
    }

    private void mergeActiveHolds(Long showId, CachedShowSeats cached) {
        ConcurrentHashMap<String, Long> holds = activeHolds.get(showId);
        if (holds != null) {
            long now = System.currentTimeMillis();
            holds.forEach((seatKey, expiresAt) -> {
                if (expiresAt > now) {
                    cached.addSeat(seatKey, CachedShowSeats.SeatStatus.HELD);
                } else {
                    holds.remove(seatKey, expiresAt);
                }
            });
            if (holds.isEmpty()) {
                activeHolds.remove(showId, holds);
            }
        }
    }

    private void removeActiveHold(Long showId, String seatKey) {
        ConcurrentHashMap<String, Long> holds = activeHolds.get(showId);
        if (holds != null) {
            holds.remove(seatKey);
            if (holds.isEmpty()) {
                activeHolds.remove(showId, holds);
            }
        }
    }

    /**
     * Get cache size (for monitoring).
     */
    public int getCacheSize() {
        return cache.size();
    }
}
