package br.edu.atitus.currencyapi.servicesimpl;

import br.edu.atitus.currencyapi.CurrencyApiApplication;
import br.edu.atitus.currencyapi.clients.BcbClient;
import br.edu.atitus.currencyapi.controllers.CurrencyController;
import br.edu.atitus.currencyapi.dtos.BcbQuote;
import br.edu.atitus.currencyapi.dtos.BcbResponse;
import br.edu.atitus.currencyapi.entities.CurrencyEntity;
import br.edu.atitus.currencyapi.repositories.CurrencyRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BcbResilienceIntegrationTest {

    private static final String DATE = "10-04-2024";
    private final BcbClient client = mock(BcbClient.class);
    private final CurrencyRepository repository = mock(CurrencyRepository.class);
    private final BcbResponse quote = new BcbResponse(List.of(new BcbQuote(5.4686, "2024-10-04 13:11:35.313")));
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    AopAutoConfiguration.class, CacheAutoConfiguration.class,
                    RetryAutoConfiguration.class, CircuitBreakerAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withBean(BcbClient.class, () -> client)
            .withBean(CurrencyRepository.class, () -> repository)
            .withPropertyValues(consulProperties());

    @Test
    void cachesOnlyValidQuotesByCurrencyAndDateBeforeResilience() {
        var unordered = new BcbResponse(List.of(
                new BcbQuote(5.4686, "2024-10-04 13:11:35.313"),
                new BcbQuote(5.4865, "2024-10-04 10:17:28.986")));
        when(client.getQuote("USD", DATE)).thenReturn(unordered);
        when(client.getQuote("USD", "10-03-2024")).thenReturn(quote);
        when(client.getQuote("EUR", DATE)).thenReturn(new BcbResponse(List.of(new BcbQuote(6.0, "2024-10-04 13:00:00"))));

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(BcbClientService.class);
            var cache = (CaffeineCache) context.getBean(CacheManager.class).getCache("bcb-api");
            assertThat(cache.getNativeCache().policy().eviction().orElseThrow().getMaximum()).isEqualTo(1000);
            assertThat(cache.getNativeCache().policy().expireAfterWrite().orElseThrow().getExpiresAfter())
                    .isEqualTo(Duration.ofMinutes(10));
            assertThat(CurrencyApiApplication.class.getAnnotation(EnableCaching.class).order()).isEqualTo(99);
            assertThat(service.getQuote("USD", DATE)).isEqualTo(5.4686);
            context.getBean(CircuitBreakerRegistry.class).circuitBreaker("bcb-api").transitionToOpenState();
            assertThat(service.getQuote("USD", DATE)).isEqualTo(5.4686);
            verify(client, times(1)).getQuote("USD", DATE);

            context.getBean(CircuitBreakerRegistry.class).circuitBreaker("bcb-api").reset();
            assertThat(service.getQuote("USD", "10-03-2024")).isEqualTo(5.4686);
            assertThat(service.getQuote("EUR", DATE)).isEqualTo(6.0);
            verify(client, times(1)).getQuote("USD", "10-03-2024");
            verify(client, times(1)).getQuote("EUR", DATE);
        });
    }

    @Test
    void retriesTransientFailuresAndCachesTheRecoveredQuote() {
        when(client.getQuote("USD", DATE)).thenThrow(new ResourceAccessException("temporary"))
                .thenThrow(new ResourceAccessException("temporary")).thenReturn(quote);

        runner.run(context -> {
            var service = context.getBean(BcbClientService.class);
            assertThat(service.getQuote("USD", DATE)).isEqualTo(5.4686);
            assertThat(service.getQuote("USD", DATE)).isEqualTo(5.4686);
            verify(client, times(3)).getQuote("USD", DATE);
            assertThat(context.getBean(CircuitBreakerRegistry.class).circuitBreaker("bcb-api")
                    .getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        });
    }

    @Test
    void usesLocalFallbackForEmptyResponsesWithoutCachingTheAbsence() {
        when(client.getQuote("USD", DATE)).thenReturn(new BcbResponse(List.of()));
        when(repository.findBySourceCurrencyAndTargetCurrency("USD", "BRL"))
                .thenReturn(Optional.of(new CurrencyEntity(1L, "USD", "BRL", 5.15)));

        runner.run(context -> {
            var service = context.getBean(CurrencyServiceJpa.class);
            var fallback = service.findBySourceCurrencyAndTargetCurrency("USD", "BRL");
            assertThat(fallback.conversionRate()).isEqualTo(5.15);
            assertThat(fallback.environment()).contains("LOCAL FALLBACK");
            assertThat(context.getBean(CacheManager.class).getCache("bcb-api").get("USD-" + DATE)).isNull();
            verify(client, times(3)).getQuote("USD", DATE);

            doReturn(quote).when(client).getQuote("USD", DATE);
            assertThat(service.findBySourceCurrencyAndTargetCurrency("USD", "BRL").conversionRate()).isEqualTo(5.4686);
            verify(client, times(4)).getQuote("USD", DATE);
        });
    }

    @Test
    void opensTheCircuitAfterFiveFailuresAndContinuesUsingTheLocalDatabase() {
        when(client.getQuote("USD", DATE)).thenThrow(new ResourceAccessException("BCB offline"));
        when(repository.findBySourceCurrencyAndTargetCurrency("USD", "BRL"))
                .thenReturn(Optional.of(new CurrencyEntity(1L, "USD", "BRL", 5.15)));

        runner.run(context -> {
            var service = context.getBean(CurrencyServiceJpa.class);
            var retryEvents = new ArrayList<String>();
            context.getBean(RetryRegistry.class).retry("bcb-api").getEventPublisher()
                    .onRetry(event -> retryEvents.add(event.getName()));
            assertThat(service.findBySourceCurrencyAndTargetCurrency("USD", "BRL").conversionRate()).isEqualTo(5.15);
            verify(client, times(3)).getQuote("USD", DATE);
            assertThat(service.findBySourceCurrencyAndTargetCurrency("USD", "BRL").conversionRate()).isEqualTo(5.15);
            verify(client, times(5)).getQuote("USD", DATE);
            assertThat(context.getBean(CircuitBreakerRegistry.class).circuitBreaker("bcb-api").getState())
                    .isEqualTo(CircuitBreaker.State.OPEN);
            int retriesBefore = retryEvents.size();
            assertThat(service.findBySourceCurrencyAndTargetCurrency("USD", "BRL").environment()).contains("LOCAL FALLBACK");
            verify(client, times(5)).getQuote("USD", DATE);
            assertThat(retryEvents).hasSize(retriesBefore);
            assertThat(context.getBean(CacheManager.class).getCache("bcb-api").get("USD-" + DATE)).isNull();

            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(CurrencyController.class)).build();
            mvc.perform(get("/currencies").param("source", "JPY").param("target", "BRL"))
                    .andExpect(status().isNotFound());
            verify(client, never()).getQuote(eq("JPY"), anyString());
        });
    }

    @Test
    void returnsHttp404WhenBcbAndTheDatabaseHaveNoQuote() {
        when(client.getQuote("USD", DATE)).thenReturn(new BcbResponse(List.of()));
        when(repository.findBySourceCurrencyAndTargetCurrency("USD", "BRL")).thenReturn(Optional.empty());

        runner.run(context -> {
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(CurrencyController.class)).build();
            mvc.perform(get("/currencies").param("source", "USD").param("target", "BRL"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("BCB nem no banco local")));
        });
    }

    @Test
    void rejectsMissingAndInvalidExternalRates() {
        var service = new BcbClientService(client);
        var invalidResponses = new ArrayList<BcbResponse>();
        invalidResponses.add(null);
        invalidResponses.add(new BcbResponse(null));
        invalidResponses.add(new BcbResponse(Arrays.asList((BcbQuote) null)));
        for (Double value : Arrays.asList(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            invalidResponses.add(new BcbResponse(List.of(new BcbQuote(value, "2024-10-04 13:00:00"))));
        }
        for (var response : invalidResponses) {
            doReturn(response).when(client).getQuote("USD", DATE);
            assertThatThrownBy(() -> service.getQuote("USD", DATE)).isInstanceOf(IllegalStateException.class);
        }
    }

    private static String[] consulProperties() {
        var pattern = Pattern.compile("consul kv put config/currency-api/([^ ]+) \"([^\"]*)\"");
        Path script = Files.exists(Path.of("../start_consul.bat")) ? Path.of("../start_consul.bat") : Path.of("start_consul.bat");
        try (var lines = Files.lines(script)) {
            return lines.map(pattern::matcher).filter(matcher -> matcher.matches())
                    .filter(matcher -> matcher.group(1).startsWith("resilience4j.")
                            || matcher.group(1).startsWith("spring.cache.") || matcher.group(1).equals("bcb.quote-date"))
                    .map(matcher -> matcher.group(1) + "=" + matcher.group(2)).toArray(String[]::new);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching(order = 99)
    @Import({BcbClientService.class, CurrencyController.class})
    static class TestConfiguration {
        @Bean
        CurrencyServiceJpa currencyServiceJpa(CurrencyRepository repository, BcbClientService bcbClientService) {
            return new CurrencyServiceJpa(repository, bcbClientService);
        }
    }
}
