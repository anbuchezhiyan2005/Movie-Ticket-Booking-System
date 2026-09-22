package dto.response;

import java.util.Map;

/**
 * Screen layout plus the currently occupied seats for a show.
 * Missing keys in occupiedSeats represent available seats.
 */
public record SeatMapResponse(
        String screenName,
        String rowRange,
        int seatsPerRow,
        Map<String, String> occupiedSeats) {
}