package org.fnm.simulator.simulations.authorizationCode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.ihe.gazelle.simulation.business.setup.Parameter;
import net.ihe.gazelle.simulation.business.setup.ParameterType;
import net.ihe.gazelle.simulation.business.setup.SimulationRequest;
import org.fnm.simulator.IUAClientSimulationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * RFC 6749 section 4.1.3: when the authorization request carried a {@code redirect_uri}, the token
 * request has to carry the identical value, or the authorization server rejects the code.
 */
class RedirectUriTest {

    private static final String REDIRECT_URI = "https://client.example.org/callback";

    /** Stands in for the authorization server under test. */
    private HttpServer authorizationServer;
    private String authorizeRedirectUri;
    private String tokenRedirectUri;

    @BeforeEach
    void startAuthorizationServer() throws IOException {
        authorizationServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        authorizationServer.createContext("/authorize", this::authorize);
        authorizationServer.createContext("/token", this::token);
        authorizationServer.start();
    }

    @AfterEach
    void stopAuthorizationServer() {
        authorizationServer.stop(0);
    }

    @Test
    void tokenRequestRepeatsTheRedirectUriOfTheAuthorizationRequest() {
        AuthorizationCodeConfig config = new AuthorizationCodeConfig("session-1", simulationRequest());
        config.redirectUri = REDIRECT_URI;

        new AuthorizationCodeRequestAction(config).run();
        assertEquals("code-1", config.authorizationCode, "the authorization step did not receive the code");

        new TokenRequestAction(config).run();

        assertEquals(REDIRECT_URI, authorizeRedirectUri);
        assertNotNull(tokenRedirectUri, "the token request did not reach the authorization server");
        assertEquals(authorizeRedirectUri, tokenRedirectUri);
    }

    private SimulationRequest simulationRequest() {
        String base = "http://127.0.0.1:" + authorizationServer.getAddress().getPort();
        return new SimulationRequest()
                .setSequenceId(IUAClientSimulationService.AUTHORIZATION_CODE_SEQUENCE_ID)
                .setTimeoutSeconds(10)
                .setSimulationParameters(List.of(
                        text("code_endpoint_url", base + "/authorize"),
                        text("token_endpoint_url", base + "/token"),
                        text("client_id", "client-id"),
                        text("client_secret", "client-secret"),
                        text("scope", "purpose_of_use=urn:oid:2.16.756.5.30.1.127.3.10.5|NORMAL")));
    }

    private static Parameter text(String name, String value) {
        return new Parameter().setName(name).setType(ParameterType.TEXT).setValue(value);
    }

    private void authorize(HttpExchange exchange) throws IOException {
        authorizeRedirectUri = formFields(exchange.getRequestURI().getRawQuery()).get("redirect_uri");
        exchange.getResponseHeaders().add("Location", authorizeRedirectUri + "?code=code-1&state=123456789");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            tokenRedirectUri = formFields(new String(body.readAllBytes(), StandardCharsets.UTF_8)).get("redirect_uri");
        }
        byte[] answer = "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(400, answer.length);
        exchange.getResponseBody().write(answer);
        exchange.close();
    }

    private static Map<String, String> formFields(String encoded) {
        Map<String, String> fields = new HashMap<>();
        if (encoded == null) return fields;
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) fields.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return fields;
    }
}
