package http;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HttpTestClient {

    private static final Pattern CSRF_TOKEN = Pattern.compile("\\\"csrfToken\\\":\\\"([^\\\"]+)\\\"");

    private final String baseUrl;
    private final HttpClient client;
    private String csrfToken;

    HttpTestClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .build();
    }

    HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        return recordAuthResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (csrfToken != null) {
            builder.header("X-CSRF-Token", csrfToken);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return recordAuthResponse(client.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    private HttpResponse<String> recordAuthResponse(HttpResponse<String> response) {
        Matcher matcher = CSRF_TOKEN.matcher(response.body());
        if (matcher.find()) {
            csrfToken = matcher.group(1);
        }
        return response;
    }
}