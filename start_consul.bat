@echo off
setlocal

set "DB_USERNAME=postgres"
set "DB_PASSWORD="
set /p "DB_PASSWORD=Informe a senha local do PostgreSQL: "
if not defined DB_PASSWORD (
    echo A senha do PostgreSQL e obrigatoria.
    exit /b 1
)

echo Iniciando Consul...
start "Consul" consul agent -dev
echo Aguardando Consul iniciar...
timeout /t 10 /nobreak > nul
echo Carregando configuracoes...

consul kv put config/product-api/server.port "8000"
consul kv put config/product-api/spring.datasource.url "jdbc:postgresql://localhost/db_product"
consul kv put config/product-api/spring.datasource.username "%DB_USERNAME%"
consul kv put config/product-api/spring.datasource.password "%DB_PASSWORD%"

consul kv put config/currency-api/server.port "8100"
consul kv put config/currency-api/spring.datasource.url "jdbc:postgresql://localhost/db_currency"
consul kv put config/currency-api/spring.datasource.username "%DB_USERNAME%"
consul kv put config/currency-api/spring.datasource.password "%DB_PASSWORD%"

consul kv put config/product-api/app.promotion.message "Promoção Semana do Cliente"

consul kv put config/product-api,en/app.promotion.message "Christmas Promotion!!!!"

echo.
echo Consul iniciado e configuracoes carregadas!
set "DB_PASSWORD="
pause
endlocal
