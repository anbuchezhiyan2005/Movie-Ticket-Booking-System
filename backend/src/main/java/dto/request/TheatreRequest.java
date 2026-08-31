package dto.request;

/*
 * DTO representing a request to create or update a theatre.
 */
public class TheatreRequest {

    private String theatreName;
    private String theatreLocation;

    public String getTheatreName() {
        return theatreName;
    }

    public void setTheatreName(String theatreName) {
        this.theatreName = theatreName;
    }

    public String getTheatreLocation() {
        return theatreLocation;
    }

    public void setTheatreLocation(String theatreLocation) {
        this.theatreLocation = theatreLocation;
    }
}
