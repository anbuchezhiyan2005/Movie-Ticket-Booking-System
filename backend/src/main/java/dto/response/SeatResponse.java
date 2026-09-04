package dto.response;

/*
 * DTO representing seat availability and info for a show.
 * Status can be: AVAILABLE, HELD (pending booking), BOOKED (confirmed).
 */
public class SeatResponse {

    private String rowLabel;
    private int seatNumber;
    private boolean available;
    private String status; // "AVAILABLE", "HELD", "BOOKED"

    public String getRowLabel() {
        return rowLabel;
    }

    public void setRowLabel(String rowLabel) {
        this.rowLabel = rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public void setSeatNumber(int seatNumber) {
        this.seatNumber = seatNumber;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
