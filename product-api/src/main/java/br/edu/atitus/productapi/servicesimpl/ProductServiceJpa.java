package br.edu.atitus.productapi.servicesimpl;

import br.edu.atitus.productapi.dtos.ProductRequest;
import br.edu.atitus.productapi.dtos.ProductResponse;
import br.edu.atitus.productapi.entities.ProductEntity;
import br.edu.atitus.productapi.repositories.ProductRepository;
import br.edu.atitus.productapi.services.ProductService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ProductServiceJpa implements ProductService {

    private final ProductRepository repository;
    private final CurrencyClientService currencyClientService;

    public ProductServiceJpa(ProductRepository repository, CurrencyClientService currencyClientService) {
        this.repository = repository;
        this.currencyClientService = currencyClientService;
    }

    @Value("${server.port:8080}")
    private String serverPort;

    @Value("${app.promotion.message:Nenhuma Promoção Ativa}")
    private String promotionMessage;

    private ProductResponse getResponse(ProductEntity entity, String environment, String targetCurrency){
        double convertedValue = entity.getPrice();
        if (! entity.getCurrency().equalsIgnoreCase(targetCurrency)) {
            try {
                var currency = currencyClientService.getCurrency(
                        entity.getCurrency(), targetCurrency);
                convertedValue = entity.getPrice() * currency.conversionRate();
                environment += " - " + currency.environment();
            } catch (Exception e) {
                convertedValue = -1;
                environment += " - ERROR: " + e.getMessage();
            }
        }
        return ProductResponse.fromEntity(
                entity,
                environment,
                promotionMessage,
                targetCurrency,
                convertedValue
        );
    }


    @Override
    public ProductResponse findById(Long id, String targetCurrency) throws Exception {
        var product = repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Produto não encontrado"));
        String environment = "Product API running in port " + serverPort;

        return getResponse(product, environment, targetCurrency);
    }

    @Override
    public Page<ProductResponse> findAll(Pageable pageable, String targetCurrency) throws Exception {
        var products = repository.findAll(pageable);
        String environment = "Product API running in port " + serverPort;

        return products.map(
                entity -> getResponse(entity, environment, targetCurrency)
        );
    }

    @Override
    public ProductEntity save(ProductRequest request) throws Exception {
        return null;
    }
}
