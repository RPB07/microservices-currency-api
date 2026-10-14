package br.edu.atitus.currencyapi.servicesimpl;

import br.edu.atitus.currencyapi.entities.CurrencyEntity;
import br.edu.atitus.currencyapi.repositories.CurrencyRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CurrencyServiceJpaTest {

    private final CurrencyRepository repository = mock(CurrencyRepository.class);
    private final BcbClientService bcb = mock(BcbClientService.class);
    private final CurrencyServiceJpa service = new CurrencyServiceJpa(repository, bcb);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(service, "quoteDate", "10-04-2024");
        when(bcb.getQuote(anyString(), anyString())).thenThrow(new IllegalStateException("BCB offline"));
    }

    @Test
    void shouldReturnCurrencyResponseWithEnvironment() throws Exception {
        var entity = new CurrencyEntity(1L, "USD", "BRL", 5.15);
        when(repository.findBySourceCurrencyAndTargetCurrency("USD", "BRL"))
                .thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(service, "serverPort", "8100");

        var response = service.findBySourceCurrencyAndTargetCurrency("USD", "BRL");

        assertEquals("USD", response.sourceCurrency());
        assertEquals("BRL", response.targetCurrency());
        assertEquals(5.15, response.conversionRate());
        assertEquals("Currency API running in Port: 8100 - LOCAL FALLBACK", response.environment());
    }

    @Test
    void shouldAcceptLowercaseCurrencyCodesAsInTheReference() throws Exception {
        var entity = new CurrencyEntity(1L, "USD", "BRL", 5.15);
        when(repository.findBySourceCurrencyAndTargetCurrency("USD", "BRL"))
                .thenReturn(Optional.of(entity));

        var response = service.findBySourceCurrencyAndTargetCurrency("usd", "brl");

        assertEquals("USD", response.sourceCurrency());
        assertEquals("BRL", response.targetCurrency());
        assertEquals(5.15, response.conversionRate());
    }

    @Test
    void shouldThrowEntityNotFoundWhenPairDoesNotExist() {
        when(repository.findBySourceCurrencyAndTargetCurrency("USD", "JPY"))
                .thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> service.findBySourceCurrencyAndTargetCurrency("USD", "JPY")
        );
    }

    @Test
    void shouldPreferBcbToTheLocalDatabase() throws Exception {
        doReturn(5.4686).when(bcb).getQuote("USD", "10-04-2024");
        var response = service.findBySourceCurrencyAndTargetCurrency("usd", "brl");
        assertEquals("USD", response.sourceCurrency());
        assertEquals("BRL", response.targetCurrency());
        assertEquals(5.4686, response.conversionRate());
        org.assertj.core.api.Assertions.assertThat(response.environment()).contains("BCB", "10-04-2024");
        verifyNoInteractions(repository);
    }

    @Test
    void shouldConvertForeignCurrenciesUsingTheirBrlRates() throws Exception {
        doReturn(5.0).when(bcb).getQuote("USD", "10-04-2024");
        doReturn(6.0).when(bcb).getQuote("EUR", "10-04-2024");
        var response = service.findBySourceCurrencyAndTargetCurrency("USD", "EUR");
        assertEquals(5.0 / 6.0, response.conversionRate(), 0.000001);
        verifyNoInteractions(repository);
    }

    @Test
    void shouldSupportBrlAsSourceWithoutCallingBcbForBrl() throws Exception {
        doReturn(5.0).when(bcb).getQuote("USD", "10-04-2024");
        assertEquals(0.2, service.findBySourceCurrencyAndTargetCurrency("BRL", "USD").conversionRate());
        verify(bcb, never()).getQuote(eq("BRL"), anyString());
    }

    @Test
    void shouldRejectAnInvalidLocalFallbackRate() {
        when(repository.findBySourceCurrencyAndTargetCurrency("USD", "BRL"))
                .thenReturn(Optional.of(new CurrencyEntity(1L, "USD", "BRL", 0.0)));
        assertThrows(EntityNotFoundException.class,
                () -> service.findBySourceCurrencyAndTargetCurrency("USD", "BRL"));
    }
}
