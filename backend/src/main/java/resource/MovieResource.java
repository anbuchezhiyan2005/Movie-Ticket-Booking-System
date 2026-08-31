package resource;

import dto.response.MovieDetailsResponse;
import dto.response.MovieResponse;
import dto.response.TheatreResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import service.MovieService;

import java.util.List;

@Path("/movies")
@Produces(MediaType.APPLICATION_JSON)
public class MovieResource {

    private final MovieService movieService;

    @Inject
    public MovieResource(MovieService movieService) {
        this.movieService = movieService;
    }

    @GET
    public List<MovieResponse> browse() {
        return movieService.browseMovies();
    }

    @GET
    @Path("/{id}")
    public MovieDetailsResponse details(@PathParam("id") Long id) {
        return movieService.getMovieDetails(id);
    }

    @GET
    @Path("/{id}/theatres")
    public List<TheatreResponse> theatres(@PathParam("id") Long id) {
        return movieService.getTheatresShowingMovie(id);
    }
}
