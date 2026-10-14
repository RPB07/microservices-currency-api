package br.edu.atitus.productapi.configs;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyClientConfigTest {

    @Test
    void stopsWaitingForASlowHttpResponseUsingTheProductionRequestFactory() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/currencies", exchange -> {
            try {
                Thread.sleep(4000);
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            // Resolve the service to a local HTTP fixture; keep the production timeouts.
            var builder = RestClient.builder().requestInterceptor((request, body, execution) ->
                    execution.execute(new HttpRequestWrapper(request) {
                        @Override
                        public URI getURI() {
                            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                                    + request.getURI().getRawPath() + "?" + request.getURI().getRawQuery());
                        }
                    }, body));
            var client = new CurrencyClientConfig().getClient(builder);

            assertThatThrownBy(() -> client.getCurrency("USD", "BRL"))
                    .isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(SocketTimeoutException.class);
        } finally {
            server.stop(0);
            executor.shutdownNow();
            executor.close();
        }
    }
}
