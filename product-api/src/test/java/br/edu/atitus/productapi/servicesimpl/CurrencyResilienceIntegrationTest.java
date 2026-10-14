package br.edu.atitus.productapi.servicesimpl;

import br.edu.atitus.productapi.ProductApiApplication;
import br.edu.atitus.productapi.clients.CurrencyClient;
import br.edu.atitus.productapi.dtos.CurrencyResponse;
import br.edu.atitus.productapi.entities.ProductEntity;
import br.edu.atitus.productapi.repositories.ProductRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CurrencyResilienceIntegrationTest {

    private final CurrencyClient client = mock(CurrencyClient.class);
    private final ProductRepository repository = mock(ProductRepository.class);
    private final CurrencyResponse rate = new CurrencyResponse("USD", "BRL", 5.15, "Currency API test");
    private final ProductEntity product = new ProductEntity(1L, "Apple", "iPhone", "Smartphone", "USD", 100, "image");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    AopAutoConfiguration.class,
                    CacheAutoConfiguration.class,
                    RetryAutoConfiguration.class,
                    CircuitBreakerAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class)
            .withBean(CurrencyClient.class, () -> client)
            .withBean(ProductRepository.class, () -> repository)
            .withPropertyValues(consulProperties());

    @Test
    void cachesSuccessfulRatesByPairBeforeRetryAndCircuitBreaker() {
        when(client.getCurrency("USD", "BRL")).thenReturn(rate);
        when(client.getCurrency("USD", "EUR"))
                .thenReturn(new CurrencyResponse("USD", "EUR", 0.87, "Currency API test"));

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(CaffeineCacheManager.class);
            assertThat(ProductApiApplication.class.getAnnotation(EnableCaching.class).order()).isEqualTo(99);
            var service = context.getBean(CurrencyClientService.class);
            var breaker = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("currency-api");

            assertThat(service.getCurrency("USD", "BRL")).isEqualTo(rate);
            breaker.transitionToOpenState();
            assertThat(service.getCurrency("USD", "BRL")).isEqualTo(rate);
            verify(client, times(1)).getCurrency("USD", "BRL");

            assertThatThrownBy(() -> service.getCurrency("USD", "EUR"))
                    .isInstanceOf(CallNotPermittedException.class);
            verify(client, never()).getCurrency("USD", "EUR");

            breaker.reset();
            assertThat(service.getCurrency("USD", "EUR").conversionRate()).isEqualTo(0.87);
            verify(client, times(1)).getCurrency("USD", "EUR");
        });
    }

    @Test
    void retriesTransientFailuresAndCachesTheRecoveredResult() {
        when(client.getCurrency("USD", "BRL"))
                .thenThrow(new ResourceAccessException("temporary failure"))
                .thenThrow(new ResourceAccessException("temporary failure"))
                .thenReturn(rate);

        runner.run(context -> {
            var service = context.getBean(CurrencyClientService.class);
            assertThat(service.getCurrency("USD", "BRL")).isEqualTo(rate);
            assertThat(service.getCurrency("USD", "BRL")).isEqualTo(rate);
            verify(client, times(3)).getCurrency("USD", "BRL");
            var metrics = context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("currency-api").getMetrics();
            assertThat(metrics.getNumberOfFailedCalls()).isEqualTo(2);
            assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        });
    }

    @Test
    void opensAfterFiveFailedAttemptsAndDoesNotRetryAnOpenCircuit() {
        when(client.getCurrency("USD", "BRL")).thenThrow(new ResourceAccessException("offline"));

        runner.run(context -> {
            var service = context.getBean(CurrencyClientService.class);
            var breaker = context.getBean(CircuitBreakerRegistry.class).circuitBreaker("currency-api");
            List<String> retries = new ArrayList<>();
            context.getBean(RetryRegistry.class).retry("currency-api")
                    .getEventPublisher().onRetry(event -> retries.add(event.getName()));

            assertThatThrownBy(() -> service.getCurrency("USD", "BRL"))
                    .isInstanceOf(ResourceAccessException.class);
            verify(client, times(3)).getCurrency("USD", "BRL");
            assertThat(context.getBean(CacheManager.class).getCache("currency-api").get("USD-BRL")).isNull();

            assertThatThrownBy(() -> service.getCurrency("USD", "BRL"))
                    .isInstanceOf(CallNotPermittedException.class);
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(5);
            verify(client, times(5)).getCurrency("USD", "BRL");

            int previousRetries = retries.size();
            assertThatThrownBy(() -> service.getCurrency("USD", "BRL"))
                    .isInstanceOf(CallNotPermittedException.class);
            assertThat(retries).hasSize(previousRetries);
            verify(client, times(5)).getCurrency("USD", "BRL");

            // Force the recovery state without waiting 100 seconds in the test.
            doReturn(rate).when(client).getCurrency("USD", "BRL");
            breaker.transitionToHalfOpenState();
            for (int i = 0; i < breaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState(); i++) {
                context.getBean(CacheManager.class).getCache("currency-api").clear();
                assertThat(service.getCurrency("USD", "BRL")).isEqualTo(rate);
            }
            assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        });
    }

    @Test
    void returnsProductWithFallbackAfterRetriesAndAnOpenCircuit() {
        when(repository.findById(1L)).thenReturn(Optional.of(product));
        when(client.getCurrency("USD", "BRL")).thenThrow(new ResourceAccessException("offline"));

        runner.run(context -> {
            var service = context.getBean(ProductServiceJpa.class);
            var response = service.findById(1L, "BRL");
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.price()).isEqualTo(100);
            assertThat(response.convertedPrice()).isEqualTo(-1);
            assertThat(response.environment()).contains("ERROR: offline");
            verify(client, times(3)).getCurrency("USD", "BRL");

            context.getBean(CircuitBreakerRegistry.class).circuitBreaker("currency-api").transitionToOpenState();
            assertThat(service.findById(1L, "BRL").convertedPrice()).isEqualTo(-1);
            verify(client, times(3)).getCurrency("USD", "BRL");
        });
    }

    @Test
    void preservesTheOriginalPriceForTheSameCurrencyAndMissingProductsStillFail() {
        when(repository.findById(1L)).thenReturn(Optional.of(product));
        when(repository.findById(99L)).thenReturn(Optional.empty());

        runner.run(context -> {
            var service = context.getBean(ProductServiceJpa.class);
            assertThat(service.findById(1L, "usd").convertedPrice()).isEqualTo(100);
            verifyNoInteractions(client);
            assertThatThrownBy(() -> service.findById(99L, "BRL"))
                    .isInstanceOf(EntityNotFoundException.class);
        });
    }

    @Test
    void convertsPaginatedProductsReusingTheSameRate() {
        var pageable = PageRequest.of(0, 10);
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(product, product), pageable, 2));
        when(client.getCurrency("USD", "BRL")).thenReturn(rate);

        runner.run(context -> {
            var response = context.getBean(ProductServiceJpa.class).findAll(pageable, "BRL");
            assertThat(response.getContent()).allSatisfy(item -> {
                assertThat(item.convertedPrice()).isEqualTo(515);
                assertThat(item.environment()).contains("Currency API test");
            });
            assertThat(response.getTotalElements()).isEqualTo(2);
            verify(client, times(1)).getCurrency("USD", "BRL");
        });
    }

    private static String[] consulProperties() {
        // Test the actual values delivered by start_consul.bat, without requiring a running agent.
        var pattern = Pattern.compile("consul kv put config/product-api/([^ ]+) \"([^\"]*)\"");
        Path script = Path.of("../start_consul.bat");
        if (!Files.exists(script)) {
            script = Path.of("start_consul.bat");
        }
        try (var lines = Files.lines(script)) {
            return lines.map(pattern::matcher).filter(matcher -> matcher.matches())
                    .filter(matcher -> matcher.group(1).startsWith("resilience4j.")
                            || matcher.group(1).startsWith("spring.cache."))
                    .map(matcher -> matcher.group(1) + "=" + matcher.group(2)).toArray(String[]::new);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableCaching(order = 99)
    @Import({CurrencyClientService.class, ProductServiceJpa.class})
    static class TestConfiguration {
    }
}
