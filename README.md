# Telecom Billing — Kotlin + Spring Batch

Motor de **tarifação e faturamento de telefonia**: processa arquivos de CDR (*Call Detail Records*) vindos da mediação de rede, tarifa voz/SMS/dados conforme o plano do assinante e consolida as faturas do ciclo.

> Segmento: **Telecomunicações**

## Stack
Kotlin 2.0 • Spring Boot 3.4 • Spring Batch 5 • JDBC • PostgreSQL • Flyway • Gradle Kotlin DSL • JUnit 5 • Spring Batch Test • H2 (testes) • Docker

## Como funciona

```
 cdr.csv ──► [Step 1: rateUsage — chunk 500]                          [Step 2: buildInvoices — tasklet]
             FlatFileItemReader → RatingEngine (Strategy) → JdbcBatch ──► agregação set-based em SQL ──► invoices
                        │
                        └─ fault tolerant: linhas corrompidas, assinante inexistente e CDR inválido são
                           ignorados (skip) e logados, sem interromper o ciclo de faturamento
```

### Regras de tarifação (`RatingEngine`)
| Uso | Regra |
|---|---|
| Voz | 1º minuto cheio + tarifação a cada 6 s; 50% de desconto fora do horário comercial e fins de semana |
| SMS | Valor unitário por plano |
| Dados | Arredondamento para blocos de 100 KB; pós-pago usa franquia (não cobra por uso) |

Cada tipo de uso é uma `RatingStrategy`: incluir roaming ou um novo produto é adicionar uma estratégia, sem mexer no pipeline.

### Decisões técnicas
- **Chunk-oriented processing** com escrita em lote (`JdbcBatchItemWriter`) para volumes de milhões de CDRs.
- **Skip policy** com limite e `SkipListener` para auditoria dos registros rejeitados.
- **Cache de assinantes** durante o job (milhões de CDRs para poucos milhares de linhas).
- **Agregação no banco** (`INSERT ... SELECT ... GROUP BY`) em vez de laço na JVM.

## Como executar
```bash
docker compose up --build
curl -X POST "localhost:8080/billing/runs?file=/data/cdr-sample.csv"
# {"executionId":1,"status":"COMPLETED","skipped":3,"rated":5}
curl localhost:8080/billing/invoices/5582999990001
```

## Testes
```bash
./gradlew test   # regras de tarifação + job completo ponta a ponta (H2)
```
