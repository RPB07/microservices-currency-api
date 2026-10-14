package br.edu.atitus.currencyapi.servicesimpl;

import br.edu.atitus.currencyapi.dtos.CurrencyResponse;
import br.edu.atitus.currencyapi.repositories.CurrencyRepository;
import br.edu.atitus.currencyapi.services.CurrencyService;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CurrencyServiceJpa implements CurrencyService {

    private static final Logger log = LoggerFactory.getLogger(CurrencyServiceJpa.class);
    private final CurrencyRepository repository;
    private final BcbClientService bcbClientService;

    @Value("${server.port:8100}")
    private String serverPort;

    @Value("${bcb.quote-date:10-04-2024}")
    private String quoteDate;

    public CurrencyServiceJpa(CurrencyRepository repository, BcbClientService bcbClientService) {
        this.repository = repository;
        this.bcbClientService = bcbClientService;
    }

    @Override
    public CurrencyResponse findBySourceCurrencyAndTargetCurrency(
            String sourceCurrency,
            String targetCurrency
    ) throws Exception {
        String source = sourceCurrency.toUpperCase();
        String target = targetCurrency.toUpperCase();
        String environment = "Currency API running in Port: " + serverPort;
        try {
            double sourceRate = source.equals("BRL") ? 1 : bcbClientService.getQuote(source, quoteDate);
            double targetRate = target.equals("BRL") ? 1 : bcbClientService.getQuote(target, quoteDate);
            double conversionRate = sourceRate / targetRate;
            if (!Double.isFinite(conversionRate) || conversionRate <= 0) {
                throw new IllegalStateException("Cotação inválida recebida do BCB");
            }
            return new CurrencyResponse(source, target, conversionRate,
                    environment + " - BCB (" + quoteDate + ")");
        } catch (Exception e) {
            log.warn("Falha na consulta BCB de {}/{} em {}. Consultando banco local: {}",
                    source, target, quoteDate, e.getMessage());
            var currency = repository.findBySourceCurrencyAndTargetCurrency(source, target)
                    .filter(entity -> entity.getConversionRate() != null
                            && Double.isFinite(entity.getConversionRate()) && entity.getConversionRate() > 0)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Cotação não encontrada no BCB nem no banco local para " + source + "/" + target));
            return CurrencyResponse.fromEntity(currency, environment + " - LOCAL FALLBACK");
        }
    }
}
