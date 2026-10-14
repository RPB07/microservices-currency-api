package br.edu.atitus.currencyapi.servicesimpl;

import br.edu.atitus.currencyapi.clients.BcbClient;
import br.edu.atitus.currencyapi.dtos.BcbQuote;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Objects;

@Service
public class BcbClientService {

    private final BcbClient bcbClient;

    public BcbClientService(BcbClient bcbClient) {
        this.bcbClient = bcbClient;
    }

    @Cacheable(value = "bcb-api", key = "#currency + '-' + #date")
    @Retry(name = "bcb-api")
    @CircuitBreaker(name = "bcb-api")
    public double getQuote(String currency, String date) {
        var response = bcbClient.getQuote(currency, date);
        if (response == null || response.value() == null) {
            throw missingQuote(currency, date);
        }
        return response.value().stream()
                .filter(Objects::nonNull)
                .filter(quote -> quote.cotacaoVenda() != null
                        && Double.isFinite(quote.cotacaoVenda()) && quote.cotacaoVenda() > 0)
                .max(Comparator.comparing(BcbQuote::dataHoraCotacao,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(BcbQuote::cotacaoVenda)
                .orElseThrow(() -> missingQuote(currency, date));
    }

    private IllegalStateException missingQuote(String currency, String date) {
        return new IllegalStateException("Cotação válida não encontrada no BCB para " + currency + " em " + date);
    }
}
