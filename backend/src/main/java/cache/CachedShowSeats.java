package cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Value object representing cached seat occupancy for a show.
 * Tracks seat status (HELD or BOOKED) with automatic TTL expiration.
 */
public class CachedShowSeats {
    private long showId;
    private Map<String, SeatStatus> occupiedSeats; // "A-1" -> HELD/BOOKED
    private long createdAt;
    private static final long TTL_MILLIS = 30_000; // 30 seconds

    public enum SeatStatus {
        HELD,    // Pending booking, temporary (user is completing payment)
        BOOKED   // Confirmed booking, permanent
    }

    public CachedShowSeats(long showId, Map<String, SeatStatus> occupiedSeats) {
        this.showId = showId;
        this.occupiedSeats = new ConcurrentHashMap<>(occupiedSeats);
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Check if this cache entry has expired (30-second TTL).
     */
    public boolean isExpired() {
        return System.currentTimeMillis() - createdAt > TTL_MILLIS;
    }

    public long getShowId() {
        return showId;
    }

    public Map<String, SeatStatus> getOccupiedSeats() {
        return occupiedSeats;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * Add a seat to occupied list (surgical update for bookings).
     */
    public void addSeat(String seatKey, SeatStatus status) {
        occupiedSeats.put(seatKey, status);
    }

    /**
     * Remove a seat from occupied list (surgical update for cancellations).
     */
    public void removeSeat(String seatKey) {
        occupiedSeats.remove(seatKey);
    }

    /**
     * Get the status of a specific seat.
     */
    public SeatStatus getSeatStatus(String seatKey) {
        return occupiedSeats.get(seatKey);
    }
}
