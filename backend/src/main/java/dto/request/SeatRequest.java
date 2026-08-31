package dto.request;

/*
 * DTO representing a request to select a specific seat by row label and seat number.
 */
public class SeatRequest {

    private String rowLabel;
    private int seatNumber;

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
}
