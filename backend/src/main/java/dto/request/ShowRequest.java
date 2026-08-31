package dto.request;

import java.time.LocalDateTime;

/*
 * DTO representing a request to create or update a show with movie, screen, and timing.
 */
public class ShowRequest {

    private Long movieId;
    private Long screenId;
    private LocalDateTime showTiming;

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
