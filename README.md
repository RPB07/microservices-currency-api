# Microsserviços — Product API e Currency API

Backend de e-commerce com consulta de produtos e cotações, seguindo a estrutura
desenvolvida em aula. A comunicação entre os serviços implementa fallback, timeout,
retry, circuit breaker e cache.

## Pré-requisitos

- Java 21 ou superior
- PostgreSQL em `localhost:5432`, com um usuário que tenha acesso aos bancos
- HashiCorp Consul disponível no `PATH`

Crie os bancos antes de iniciar os serviços:

```sql
CREATE DATABASE db_product;
CREATE DATABASE db_currency;
```

## Execução

1. Execute `start_consul.bat` para iniciar o Consul e cadastrar as configurações.
   A senha presente no script é fictícia e pode ser substituída pela senha do
   ambiente em que os microsserviços forem executados.
2. Em um terminal, entre em `currency-api` e execute `.\mvnw.cmd spring-boot:run`.
3. Em outro terminal, entre em `product-api` e execute `.\mvnw.cmd spring-boot:run`.

Os serviços seguem a integração do [commit de referência](https://github.com/luciano-ferretto/ParadLingProg_202602/commit/31fcb043188f8df119ec65ce014ba8ced3912cf8).
O Consul é obrigatório para configuração e descoberta dos serviços. A Product API usa
`CurrencyClient`, configurado em `CurrencyClientConfig` com `RestClient` e `@LoadBalanced`,
para acessar `http://currency-api/currencies` pelo nome registrado no Consul.
O preço convertido é o preço do produto multiplicado pela taxa; para a mesma moeda,
o preço original é preservado sem chamada à Currency API.

Exemplos de consulta de produtos:

```text
GET http://localhost:8000/products/1?targetCurrency=BRL
GET http://localhost:8000/products?targetCurrency=BRL&page=0&size=10
```

O Flyway cria e popula as tabelas automaticamente. Para testar a nova API:

```text
GET http://localhost:8100/currencies?source=USD&target=BRL
```

Resposta esperada:

```json
{
  "sourceCurrency": "USD",
  "targetCurrency": "BRL",
  "conversionRate": 5.15,
  "environment": "Currency API running in Port: 8100"
}
```

## Resiliência

O fluxo de conversão é `ProductServiceJpa → CurrencyClientService → CurrencyClient`.
O serviço intermediário concentra as anotações de cache e Resilience4j. O fallback
permanece na conversão do produto, após o esgotamento das tentativas ou a rejeição
pelo circuito aberto.

| Padrão | Configuração e comportamento |
| --- | --- |
| Fallback | Retorna o produto com `convertedPrice = -1` e acrescenta `ERROR` ao `environment` quando a conversão falha. |
| Timeout | `SimpleClientHttpRequestFactory`: conexão de 1 segundo e leitura de 2 segundos por tentativa. |
| Retry | Até 3 tentativas, incluindo a inicial, com intervalo de 1 segundo. Ignora `CallNotPermittedException`. |
| Circuit breaker | Janela de 5 chamadas, mínimo de 5, limite de falhas de 50% e espera de 100 segundos no estado aberto. |
| Cache | Caffeine, chave `source-target`, até 1.000 entradas e expiração de 10 minutos após a gravação. |

A ordem é **Cache (99) → Retry (100) → Circuit Breaker (101)**. Assim, um acerto
no cache dispensa os demais interceptadores, inclusive quando o circuito está aberto.
Cada tentativa do retry conta separadamente na janela do circuit breaker.
Exceções não são armazenadas no cache; o marcador `-1` é produzido fora dele.
Para a mesma moeda, o preço original é retornado sem consultar o serviço remoto.

Todas as configurações externas da atividade estão em `start_consul.bat`, nas chaves
`config/application/` (Actuator compartilhado) e `config/product-api/` (resiliência e cache).
Execute novamente o script e reinicie os serviços após atualizar essas configurações.
O marcador `-1` e a exposição ampla do Actuator são usados para o exercício em desenvolvimento.

## Aderência ao enunciado e à base da aula

Os 19 comandos Key/Value exigidos no enunciado foram comparados com o script:
todos estão presentes, com os mesmos nomes e valores. Foram preservados a
estrutura de pacotes, o cliente HTTP declarativo, o `RestClient` com `@LoadBalanced`
e o fluxo de conversão do commit de referência.

O fallback usa o `try/catch` em `ProductServiceJpa`; `CurrencyClientService`
concentra exatamente `@Cacheable`, `@Retry` e `@CircuitBreaker`; a classe principal
habilita o cache com ordem 99. Os starters de cache e AspectJ habilitam o suporte
necessário às anotações. O BOM do Resilience4j mantém todos os módulos em 2.4.0,
evitando que o BOM do Spring Cloud selecione módulos 2.3.0 para o starter 2.4.0.

Na revisão da base, a Currency API foi alinhada ao professor em dois pontos:
normalização das moedas com `toUpperCase()` e geração do ID com `IDENTITY`,
compatível com o `SERIAL` da migração. Os wrappers receberam um ajuste de
compatibilidade para diretórios sem destino de link no PowerShell do Windows.

O mapeamento dos campos de `ProductResponse` e o `save()` incompleto já constam
na base do professor e foram preservados; a atividade acrescenta resiliência
ao fluxo de consulta, sem implementar novas operações de cadastro.

## Testes automatizados e resultados

Na raiz, execute:

```powershell
.\product-api\mvnw.cmd -f product-api/pom.xml test
.\currency-api\mvnw.cmd -f currency-api/pom.xml test
```

Os testes não exigem PostgreSQL nem Consul em execução. Os testes de resiliência
carregam os valores do próprio `start_consul.bat` em um contexto Spring com AOP,
Resilience4j e Caffeine reais; o cliente remoto e o repository são simulados.
O teste de timeout usa o cliente HTTP de produção contra um servidor HTTP local
que demora 4 segundos para responder.

Validação realizada: **10 testes aprovados, sem falhas** (7 na Product API e 3 na Currency API).
As **29 chaves** de `start_consul.bat` também foram gravadas e conferidas via API
em um Consul temporário na porta 18500, encerrado ao final. Essa verificação
valida os comandos Key/Value sem alterar o agente usado pelos serviços.

| Cenário verificado | Resultado |
| --- | --- |
| Cotação bem-sucedida e cache por par | A consulta repetida reutiliza a resposta, mesmo com circuito aberto; outro par continua protegido pelo circuito. O Caffeine recebe limite de 1.000 entradas e expiração de 10 minutos. |
| Duas falhas temporárias e recuperação | Exatamente 3 chamadas; a resposta recuperada é armazenada no cache. |
| Falhas persistentes | O circuito abre após 5 tentativas falhas; novas consultas não chamam o cliente nem geram novos retries. |
| Recuperação do circuito | A transição para HALF_OPEN é forçada no teste; chamadas bem-sucedidas fecham o circuito. |
| Fallback | O produto mantém ID e preço original e retorna conversão `-1`, tanto após retries quanto com circuito aberto. |
| Mesma moeda e produto inexistente | Não há chamada remota para a mesma moeda; produto inexistente continua gerando erro. |
| Listagem paginada | Produtos com o mesmo par reutilizam uma única cotação; preço 100 × taxa 5,15 retorna 515. |
| Timeout HTTP | A leitura da resposta atrasada falha com `SocketTimeoutException`, capturada como `ResourceAccessException`. |
| Currency API | Consulta existente retorna a taxa e o ambiente; aceita códigos em minúsculas, como na referência; par inexistente lança `EntityNotFoundException`. |

Também foi realizada uma conferência com os dois JARs, PostgreSQL e Consul reais,
em instâncias temporárias e portas separadas: PostgreSQL 15432, Consul 18500,
Product API 18000 e Currency API 18100. Os serviços locais existentes foram
preservados. O Flyway criou e populou os dois bancos, o Actuator indicou saúde
`UP` e a Currency API foi descoberta pelo nome no Consul.

| Cenário com os serviços reais | Resultado observado |
| --- | --- |
| Conversão via descoberta | Produto de USD 699 convertido para BRL 3.599,85 com taxa 5,15. |
| Cache durante indisponibilidade | A Currency API foi encerrada; a consulta previamente armazenada continuou retornando o preço convertido. |
| Fallback e retry | Com uma Product API recém-iniciada e a Currency API parada, ocorreram 3 tentativas na primeira consulta e mais 2 na segunda; a resposta manteve o produto com conversão `-1`. |
| Circuito aberto | Os eventos do Actuator confirmaram 5 falhas e a transição para OPEN. Novas consultas não acrescentaram tentativas remotas nem retries. |
| Recuperação automática | Após aguardar o intervalo real de 100 segundos, dez conversões sem cache levaram o circuito de HALF_OPEN a CLOSED, sem forçar as transições. |
| Falha temporária | Um bloqueio de 4 segundos na tabela de cotações fez a primeira tentativa atingir o timeout; o retry recuperou a conversão. |
| Lentidão persistente | Um bloqueio de 15 segundos na tabela resultou em dois timeouts de leitura registrados no Actuator. O circuito abriu e a Product API retornou o fallback em aproximadamente 6,04 segundos. |

O número de tentativas durante uma falha depende também das chamadas já
registradas na janela. Se o circuit breaker abrir durante os retries, a próxima
tentativa é rejeitada e o fallback pode indicar circuito aberto em vez de timeout.

## Conferência com os serviços em execução

O script habilita os endpoints de monitoramento nos dois serviços. Na Product API,
consulte `/actuator/health`, `/actuator/caches`, `/actuator/circuitbreakers`,
`/actuator/circuitbreakerevents` e `/actuator/retryevents`.

1. Com ambos os serviços ativos, consulte `/products/1?targetCurrency=BRL`.
   A cotação inicial USD/BRL é 5,15. Repita a consulta para preencher e reutilizar o cache.
2. Interrompa a Currency API. A mesma consulta ainda usa a cotação já armazenada.
3. Limpe o cache para testar falhas sem aguardar os 10 minutos:

   ```powershell
   Invoke-RestMethod -Method Delete -Uri 'http://localhost:8000/actuator/caches/currency-api'
   ```

4. Consulte novamente o produto. Deve retornar HTTP 200, `convertedPrice = -1`
   e `ERROR` no ambiente. Repita até abrir o circuito; as tentativas registradas
   podem ser observadas pelos endpoints de eventos. A janela considera sucessos
   anteriores e cada tentativa do retry. Em uma instância recém-iniciada,
   sem chamadas anteriores, a primeira consulta faz 3 tentativas falhas e a
   segunda completa as 5 falhas que abrem o circuito.
5. Com o circuito aberto, novas consultas sem cache devem retornar o fallback
   sem novas chamadas remotas. Reinicie a Currency API, aguarde os 100 segundos
   e faça novas consultas para acompanhar HALF_OPEN e CLOSED. Acertos no cache
   não contam como chamadas de recuperação; limpe-o entre essas consultas.

O teste automatizado de timeout oferece uma simulação de lentidão reproduzível:
execute a Product API com `-Dtest=CurrencyClientConfigTest test` pelo Maven.
O teste `CurrencyResilienceIntegrationTest` demonstra falhas temporárias, retries,
fallback, abertura do circuito, recuperação e cache em conjunto.
