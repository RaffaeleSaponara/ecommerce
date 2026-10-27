package com.TreDL.ecommerce.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class HomePageHttpTests {
    @LocalServerPort
    private int port;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/home"})
    void freshAnonymousHomePageRendersCompletelyAndProvidesWorkingCsrf(String path) throws Exception {
        // Real Tomcat commits a full response buffer; MockMvc does not reproduce that behavior.
        // No cookie manager: every invocation starts without an existing HTTP session.
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> page = client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("</body>");
        assertThat(page.body().trim()).endsWith("</html>");
        assertThat(page.body()).doesNotContain("Whitelabel Error Page");
        var token = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page.body());
        assertThat(token.find()).as("rendered CSRF hidden field").isTrue();
        String cookie = page.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID="))
                .map(value -> value.split(";", 2)[0]).findFirst().orElseThrow();

        HttpResponse<String> logout = logout(client, cookie,
                "_csrf=" + URLEncoder.encode(token.group(1), StandardCharsets.UTF_8));
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(uri("/").resolve(logout.headers().firstValue("location").orElseThrow()))
                .isEqualTo(uri("/"));
    }

    private HttpResponse<String> logout(HttpClient client, String cookie, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri("/logout")).timeout(Duration.ofSeconds(10))
                        .header("Cookie", cookie)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
