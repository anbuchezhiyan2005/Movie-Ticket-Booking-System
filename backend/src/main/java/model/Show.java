package model;

import java.time.LocalDateTime;

/*
 * Represents a specific movie screening on a screen at a certain time.
 */
public class Show {

    private Long showId;
    private Long movieId;
    private Long screenId;
    private LocalDateTime showTiming;

    public Long getShowId() {
        return showId;
    }

    public void setShowId(Long showId) {
        this.showId = showId;
    }

    public Long getMovieId() {
        return movieId;
    }

    public void setMovieId(Long movieId) {
        this.movieId = movieId;
    }

    public Long getScreenId() {
        return screenId;
    }

    public void setScreenId(Long screenId) {
        this.screenId = screenId;
    }

    public LocalDateTime getShowTiming() {
        return showTiming;
    }

    public void setShowTiming(LocalDateTime showTiming) {
        this.showTiming = showTiming;
    }
}
