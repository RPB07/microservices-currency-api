# Microsserviços — Product API e Currency API

Entrega da atividade de criação do `currency-api`, seguindo a estrutura do `product-api`
desenvolvido em aula.

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

1. Execute `start_consul.bat`, informe a senha local do PostgreSQL e aguarde o
   script iniciar o Consul e cadastrar as configurações.
2. Em um terminal, execute `product-api\mvnw.cmd spring-boot:run`.
3. Em outro terminal, execute `currency-api\mvnw.cmd spring-boot:run`.

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
