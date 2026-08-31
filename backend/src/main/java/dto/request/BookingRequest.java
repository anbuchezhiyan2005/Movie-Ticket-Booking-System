package dto.request;

import java.util.List;

/*
 * DTO representing a request to book tickets for a specific show and list of seats.
 */
public class BookingRequest {

    private Long showId;
    private List<SeatRequest> seats;

    public Long getShowId() {
        return showId;
    }

    public void setShowId(Long showId) {
        this.showId = showId;
    }

    public List<SeatRequest> getSeats() {
        return seats;
    }

    public void setSeats(List<SeatRequest> seats) {
        this.seats = seats;
    }
}
