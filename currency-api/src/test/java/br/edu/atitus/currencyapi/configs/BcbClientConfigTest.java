package br.edu.atitus.currencyapi.configs;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class BcbClientConfigTest {

    @Test
    void sendsTheRequiredODataParametersAndReadsTheBcbJson() throws Exception {
        var requestUri = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUri.set(exchange.getRequestURI().toString());
            var body = """
                    {"@odata.context":"metadata", "value":[
                      {"cotacaoCompra":5.4680,"cotacaoVenda":5.4686,
                       "dataHoraCotacao":"2024-10-04 13:11:35.313","tipoBoletim":"Fechamento PTAX"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new BcbClientConfig().getClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
            var response = client.getQuote("USD", "10-04-2024");

            assertThat(response.value()).hasSize(1);
            assertThat(response.value().getFirst().cotacaoVenda()).isEqualTo(5.4686);
            assertThat(java.net.URLDecoder.decode(requestUri.get(), StandardCharsets.UTF_8))
                    .contains("/CotacaoMoedaDia(moeda=@moeda,dataCotacao=@dataCotacao)",
                            "@moeda='USD'", "@dataCotacao='10-04-2024'", "$format=json");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutAResponseSlowerThanFiveSeconds() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(8000);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new BcbClientConfig().getClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
            assertThatThrownBy(() -> client.getQuote("USD", "10-04-2024"))
                    .isInstanceOf(ResourceAccessException.class).hasRootCauseInstanceOf(SocketTimeoutException.class);
        } finally {
            server.stop(0);
            executor.shutdownNow();
            executor.close();
        }
    }
}
