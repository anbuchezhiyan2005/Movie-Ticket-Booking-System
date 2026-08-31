package dto.request;

/*
 * DTO representing a request to create or update a screen within a theatre.
 */
public class ScreenRequest {

    private Long theatreId;
    private String screenName;
    private String rowRange;
    private int seatsPerRow;

    public Long getTheatreId() {
        return theatreId;
    }

    public void setTheatreId(Long theatreId) {
        this.theatreId = theatreId;
    }

    public String getScreenName() {
        return screenName;
    }

    public void setScreenName(String screenName) {
        this.screenName = screenName;
    }

    public String getRowRange() {
        return rowRange;
    }

    public void setRowRange(String rowRange) {
        this.rowRange = rowRange;
    }

    public int getSeatsPerRow() {
        return seatsPerRow;
    }

    public void setSeatsPerRow(int seatsPerRow) {
        this.seatsPerRow = seatsPerRow;
    }
}
