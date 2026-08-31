package model;

/*
 * Represents a screen within a theatre, defining its name and seat layout.
 */
public class Screen {

    private Long screenId;
    private Long theatreId;
    private String screenName;
    private String rowRange;
    private int seatsPerRow;

    public Long getScreenId() {
        return screenId;
    }

    public void setScreenId(Long screenId) {
        this.screenId = screenId;
    }

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
