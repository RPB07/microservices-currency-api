# Microsserviços — Product API e Currency API

Backend de e-commerce com consulta de produtos e cotações, seguindo a estrutura
desenvolvida em aula. A Currency API consulta a PTAX do Banco Central do Brasil
e usa o PostgreSQL como fallback. As duas comunicações HTTP implementam
fallback, timeout, retry, circuit breaker e cache.

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

O Flyway cria e popula as tabelas locais de fallback automaticamente. Para consultar uma cotação:

```text
GET http://localhost:8100/currencies?source=USD&target=BRL
```

Com o BCB disponível, a data fixa configurada é **04/10/2024** (formato da API:
`MM-dd-yyyy`, portanto `10-04-2024`). A resposta esperada para USD/BRL é:

```json
{
  "sourceCurrency": "USD",
  "targetCurrency": "BRL",
  "conversionRate": 5.4686,
  "environment": "Currency API running in Port: 8100 - BCB (10-04-2024)"
}
```

## Currency API → Banco Central

O fluxo é `CurrencyServiceJpa → BcbClientService → BcbClient`. O cliente declarativo
usa `@HttpExchange`, `@GetExchange` e `RestClient`, configurado em `BcbClientConfig`.
Ele consulta o endpoint exigido no enunciado:

```text
https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata/CotacaoMoedaDia(moeda=@moeda,dataCotacao=@dataCotacao)?@moeda='{MOEDA}'&@dataCotacao='{DATA}'&$format=json
```

Os DTOs interpretam a lista `value` e a cotação de venda. Entre os registros
válidos, é selecionado o boletim de data/hora mais recente. Valores ausentes,
nulos, zero, negativos e não finitos são rejeitados antes de entrar no cache.

A [documentação de dados do BCB](https://www.bcb.gov.br/conteudo/dadosabertos/BCBDepin/gnastportal-dados-abertostaxas-de-cambio---todos-os-boletins-diarios.pdf)
define a cotação de venda em reais por unidade da moeda consultada. Para preservar
os pares consumidos pela Product API, o cálculo é `cotacaoBRL(origem) / cotacaoBRL(destino)`.
A cotação de BRL em BRL vale 1 e dispensa consulta externa. Assim, USD/BRL usa
a cotação de USD, BRL/USD usa seu inverso e USD/EUR divide as duas cotações.

| Padrão | Configuração |
| --- | --- |
| Fallback | Falha externa, ausência de dados ou circuito aberto levam à consulta do par no banco local. Sem cotação válida nas duas fontes, retorna HTTP 404. |
| Timeout | Conexão de 2 segundos e leitura de 5 segundos, por tentativa. |
| Retry | Até 3 tentativas, incluindo a inicial, com intervalo de 1 segundo; ignora `CallNotPermittedException`. |
| Circuit breaker | `COUNT_BASED`, janela e mínimo de 5 chamadas, limite de falhas de 50% e intervalo aberto de 100 segundos. |
| Cache | Caffeine, `bcb-api`, chave `moeda-data`, limite de 1.000 entradas e expiração de 10 minutos após a gravação. |

A ordem segue a atividade anterior: **Cache (99) → Retry (100) → Circuit Breaker (101)**.
O cache do BCB armazena apenas taxas externas válidas. Respostas vazias e falhas
lançam exceção; o fallback local acontece fora desse método e não é gravado nesse cache.
Uma resposta vazia também passa pelas tentativas e pela contabilização de falhas.

O endpoint `/currencies?source=...&target=...` mantém os quatro campos existentes.
O `environment` indica `BCB (data)` ou `LOCAL FALLBACK`. Por exemplo, se o BCB
falhar, o par USD/BRL cadastrado na migração retorna 5,15 em vez de 5,4686.
Os avisos de fallback são registrados nos logs.

As configurações estão em `config/currency-api/` no `start_consul.bat`:
`bcb.base-url`, `bcb.quote-date`, instância de resiliência `bcb-api` e cache `bcb-api`.
Ao alterá-las, reinicie a Currency API. A data fixa é didática e representa uma
cotação histórica; não há busca automática por dias úteis ou pela cotação atual.

Para observar as chamadas ao BCB, consulte os endpoints da **Currency API**:
`/actuator/circuitbreakerevents/bcb-api`, `/actuator/retryevents/bcb-api`,
`/actuator/circuitbreakers` e `/actuator/caches`.

O cache da Product API pode responder antes de chegar à Currency API. Para testar
uma nova consulta externa, limpe os dois caches:

```powershell
Invoke-RestMethod -Method Delete -Uri 'http://localhost:8000/actuator/caches/currency-api'
Invoke-RestMethod -Method Delete -Uri 'http://localhost:8100/actuator/caches/bcb-api'
```

Os timeouts da Product API continuam sendo os da aula anterior (conexão de 1 segundo
e leitura de 2 segundos). Em uma demora prolongada no BCB, a Product API pode
atingir seu próprio limite e retornar `convertedPrice = -1` antes da conclusão
do fallback local da Currency API. Os testes de timeout externo consultam a
Currency API ou o cliente BCB diretamente para verificar o limite de 5 segundos.

## Resiliência Product API → Currency API

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

Os 19 comandos Key/Value exigidos na atividade anterior foram comparados com o script:
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

Validação atual: **22 testes aprovados, sem falhas** (7 na Product API e 15 na Currency API).
O script possui 44 chaves, incluindo 15 novas configurações da integração BCB.
Os testes de integração Spring carregam as configurações do próprio script.

| Cenário exigido na atividade BCB | Verificação |
| --- | --- |
| 1. Moeda com cotação | JSON OData válido é interpretado; o boletim mais recente é escolhido e o BCB tem preferência sobre o banco. |
| 2. Sem cotação externa | Lista `value` vazia aciona fallback local; a ausência não entra no cache e uma consulta posterior pode recuperar a taxa externa. |
| 3. Falha de comunicação | Duas falhas temporárias são seguidas de sucesso na terceira tentativa; o resultado recuperado é armazenado. |
| 4. Lentidão | Servidor HTTP local atrasa 8 segundos; o cliente de produção lança `SocketTimeoutException` após o limite de leitura de 5 segundos. |
| 5. Abertura do circuito | Após 5 tentativas falhas, o circuito passa a OPEN; não há novas chamadas externas nem retries de chamadas rejeitadas. |
| 6. Fallback com circuito aberto | O serviço continua usando o par do PostgreSQL; sem par local, o controller responde HTTP 404. |
| 7. Cache | Repetição reutiliza a taxa, inclusive com circuito aberto. Moedas e datas diferentes têm chaves próprias; tamanho e expiração são conferidos no Caffeine real. |
| 8. Ausência nas duas fontes | O controller retorna HTTP 404 quando o BCB não tem dados e o banco não possui o par. |

Os testes também cobrem conversão cruzada, BRL como origem, códigos em minúsculas
e rejeição de cotações externas e locais inválidas. Os testes abaixo da Product API
permanecem válidos e verificam a comunicação interna e seus padrões de resiliência.

Foi executado ainda um teste com os dois aplicativos reais, PostgreSQL e Consul
temporários, usando as 44 chaves do script e a API pública do BCB:

| Verificação completa da atividade BCB | Resultado observado |
| --- | --- |
| Consulta externa real | A Currency API retornou USD/BRL 5,4686 para 04/10/2024, identificando o BCB no ambiente. |
| Product → Consul → Currency → BCB | O produto de USD 699 foi convertido para BRL 3.822,5514 mantendo o contrato HTTP existente. |
| Conversão cruzada real | USD/EUR retornou aproximadamente 0,9123304583 e foi consumido corretamente pela Product API. |
| Indisponibilidade externa | Após apontar somente o Consul temporário para uma porta local sem servidor BCB, 5 tentativas abriram o circuito. O PostgreSQL forneceu USD/BRL 5,15, recebido também pela Product API. |
| Ausência nas duas fontes | Com o circuito do BCB aberto, USD/JPY retornou HTTP 404 por não existir no banco local. |

As instâncias temporárias foram encerradas ao final. O código da Product API
permaneceu inalterado nesta atividade.

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
| Currency API | Prioriza o BCB, calcula os pares, normaliza moedas, usa o banco em falhas e rejeita fallback local inválido. |

Na atividade anterior, foi realizada uma conferência com os dois JARs, PostgreSQL e Consul reais,
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

## Conferência da comunicação interna com os serviços em execução

O script habilita os endpoints de monitoramento nos dois serviços. Na Product API,
consulte `/actuator/health`, `/actuator/caches`, `/actuator/circuitbreakers`,
`/actuator/circuitbreakerevents` e `/actuator/retryevents`.

1. Com ambos os serviços ativos, consulte `/products/1?targetCurrency=BRL`.
   A cotação histórica do BCB para USD/BRL é 5,4686; o fallback local vale 5,15.
   Repita a consulta para preencher e reutilizar o cache.
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
