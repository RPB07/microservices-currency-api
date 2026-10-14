package br.edu.atitus.currencyapi.servicesimpl;

import br.edu.atitus.currencyapi.entities.CurrencyEntity;
import br.edu.atitus.currencyapi.repositories.CurrencyRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrencyServiceJpaTest {

    private final CurrencyRepository repository = mock(CurrencyRepository.class);
    private final CurrencyServiceJpa service = new CurrencyServiceJpa(repository);

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
        assertEquals("Currency API running in Port: 8100", response.environment());
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
}
