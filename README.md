# Ticket System — venda de ingressos com alta concorrência

[![CI](https://github.com/Thg1zZ/TicketSystem/actions/workflows/ci.yml/badge.svg)](https://github.com/Thg1zZ/TicketSystem/actions/workflows/ci.yml)

API backend para a **abertura de vendas de um show**: milhares de pessoas tentando comprar os mesmos
assentos no mesmo segundo. O sistema tem duas regras inegociáveis:

1. **nunca vender o mesmo assento duas vezes;**
2. **nunca cobrar o mesmo pagamento duas vezes.**

O diferencial do projeto é **implementar quatro estratégias de controle de concorrência, trocáveis por
configuração, e compará-las com teste de carga (k6)** — com prova, via SQL, de zero vendas duplicadas.

> Java 21 · Spring Boot 4.1 · PostgreSQL 17 + Flyway · Redis 8 · RabbitMQ 4 · Testcontainers · k6 · Docker Compose
> · 97 testes · 95% de cobertura de linhas · licença MIT

---

## Sumário
- [Demo visual](#demo-visual)
- [Arquitetura](#arquitetura)
- [Fluxo de compra](#fluxo-de-compra)
- [Estratégias de concorrência](#estratégias-de-concorrência)
- [Resultados do teste de carga](#resultados-do-teste-de-carga)
- [Como as garantias são asseguradas](#como-as-garantias-são-asseguradas)
- [Segurança](#segurança)
- [Decisões técnicas](#decisões-técnicas)
- [API](#api)
- [Como rodar](#como-rodar)
- [Testes](#testes)
- [Limitações conhecidas e próximos passos](#limitações-conhecidas-e-próximos-passos)

---

## Demo visual

Com a stack no ar, abra **http://localhost:8081/** — uma página em HTML, CSS e JavaScript puros (sem framework nem
build), servida pela própria aplicação e usando só a API pública:

- **mapa de assentos** com palco e cores por estado (livre, selecionado, seu, reservado, vendido), atualizado a cada 2 s;
- **fluxo completo:** escolher até 4 assentos → reservar (contador de 10:00) → pagar (a `Idempotency-Key` é gerada e
  exibida; pagar de novo nunca cobra duas vezes) → ingressos com **QR code** emitidos via RabbitMQ → cancelar;
- **atividade da API** ao lado: cada chamada com status e mensagem (201, 409, 402…), para ver o backend reagindo;
- tema claro/escuro automático e layout para celular.

**Demonstração de concorrência:** abra duas abas (cada aba é um comprador diferente, mude o nome no topo se quiser),
selecione o mesmo assento nas duas e clique em *Reservar* nas duas. Uma ganha; a outra recebe **409** e o assento
pisca em vermelho. Criar eventos pede o token de admin (`ADMIN_TOKEN` do `.env`).

Por segurança, todo texto vindo da API entra na página via `textContent` (nunca `innerHTML`) e o token de admin não é
salvo no navegador.

## Arquitetura

```mermaid
flowchart LR
    client([Cliente / k6]) -->|REST + X-User-Id| api

    subgraph app[Spring Boot app]
        api[Controllers<br/>ProblemDetail RFC 9457]
        hold[reservation<br/>HoldService + Strategy]
        checkout[checkout<br/>CheckoutService]
        gateway[payment<br/>FakePaymentGateway]
        relay[messaging<br/>OutboxRelay]
        ticketing[ticketing<br/>emissão + QR]
        notify[notification<br/>e-mail simulado]
        jobs[jobs<br/>expiração de holds<br/>recuperação de pagamentos]
    end

    api --> hold
    api --> checkout
    checkout --> gateway
    hold -->|estratégia redis| redis[(Redis<br/>claims com TTL)]
    hold --> pg[(PostgreSQL<br/>fonte da verdade)]
    checkout -->|pedido + outbox<br/>mesma transação| pg
    relay -->|lê outbox| pg
    relay -->|publisher confirms| mq{{RabbitMQ<br/>ticket.events}}
    mq -->|order.confirmed| ticketing
    ticketing -->|tickets.issued via outbox| pg
    mq -->|tickets.issued / order.cancelled| notify
    mq -.->|falhou 3x| dlq[(DLQs)]
    jobs --> pg
```

Código organizado **por feature** (`event`, `reservation`, `checkout`, `payment`, `ticketing`, `notification`,
`messaging`, `shared`), não por camada.

## Fluxo de compra

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuário
    participant API
    participant DB as PostgreSQL
    participant GW as Gateway (fake)
    participant MQ as RabbitMQ

    U->>API: POST /events/{id}/holds {seatIds}
    API->>DB: estratégia ativa reserva TODOS os assentos ou nenhum
    API-->>U: 201 hold (expira em 10 min) / 409 assento indisponível

    U->>API: POST /holds/{id}/checkout + Idempotency-Key
    API->>DB: tx1: cria pedido (ou acha o da chave) e trava o hold
    API->>GW: charge(chave = id do pedido) — FORA de transação
    GW-->>API: aprovado / recusado
    API->>DB: tx2: assentos SOLD + itens ativos + evento no outbox
    API-->>U: 201 CONFIRMED / 402 recusado / 503 resultado incerto

    DB-->>MQ: OutboxRelay publica order.confirmed
    MQ->>API: emissão de ingressos (idempotente) → tickets.issued
    MQ->>API: e-mail simulado
```

## Estratégias de concorrência

Selecionadas por `ticket.reservation.strategy` (no Docker: `STRATEGY=redis docker compose up -d`).
Todas passam pelo **mesmo contrato de testes** (`ReservationStrategyContract`): 50 threads no mesmo assento →
exatamente 1 vencedor; pedidos sobrepostos em ordem invertida → sem deadlock; hold expirado pode ser tomado;
hold liberado pode ser reservado na hora.

| Estratégia | Como funciona | A favor | Contra |
|---|---|---|---|
| `pessimistic` (padrão) | `SELECT … FOR UPDATE OF s ORDER BY id`: trava as linhas dos assentos | Simples, correto por construção | Quem chega depois **espera** segurando conexão do pool |
| `optimistic` | Lê sem lock e grava com `@Version` (`WHERE version = ?`) | Ninguém espera | Sob disputa forte, muito trabalho é desperdiçado; sem retry (o assento já tem dono) |
| `conditional` | Um único `UPDATE … WHERE status disponível` e confere as linhas afetadas | O banco faz "checar e gravar" atomicamente, 1 ida ao banco | Não diz qual assento falhou num pedido múltiplo |
| `redis` | `MGET` rejeita antes da transação quem já perdeu; script Lua tudo-ou-nada decide; o banco confirma com UPDATE condicional | Perdedores nem pegam conexão do Postgres | Mais um componente para operar; precisa de compensação se o banco der rollback |

## Resultados do teste de carga

**Cenário** ([`loadtest/seat-rush.js`](loadtest/seat-rush.js)): **5.000 compradores distintos, 1.000 ao mesmo tempo,
disputando 500 assentos** (1 setor, 20 fileiras × 25). Cada um quer 1 ou 2 assentos vizinhos sorteados; quem ganha o
hold paga (gateway fake com 50–300 ms de latência e 10% de recusa). Antes de cada medição, banco, Redis e RabbitMQ
são zerados e um aquecimento de 1.000 usuários roda num evento separado (descartado). Duas rodadas por estratégia,
medidas com o código final (depois da revisão de segurança).

**Ambiente:** Windows 10 + Docker Desktop (VM com 12 CPUs e 8 GB) — app, PostgreSQL, Redis, RabbitMQ **e o próprio k6
na mesma máquina**, com outro projeto rodando ao lado. Pool Hikari padrão (10 conexões), virtual threads ligadas.
Os números servem para **comparar estratégias**, não como capacidade absoluta.

Latência e throughput da reserva (`POST /events/{id}/holds`), medidos pelo k6 — rodada 1 / rodada 2:

| Estratégia | Duração | Throughput (holds/s) | p50 | p95 | p99 | Erros inesperados | Assentos vendidos 2× |
|---|---|---|---|---|---|---|---|
| `pessimistic` | 7,8 s / 12,8 s | 645 / 392 | 869 / 1.900 ms | 2.148 / 3.645 ms | 3.408 / 4.628 ms | **0%** | **0** |
| `optimistic` | 9,8 s / 8,4 s | 512 / 593 | 1.204 / 1.045 ms | 2.425 / 2.235 ms | 3.725 / 3.141 ms | **0%** | **0** |
| `conditional` | 16,2 s / 5,1 s | 308 / 975 | 1.822 / 571 ms | 5.187 / 1.258 ms | 7.103 / 1.994 ms | **0%** | **0** |
| `redis` | **4,4 s / 5,7 s** | **1.140 / 879** | **10 / 4 ms** | 1.999 / 2.400 ms | 2.664 / 2.830 ms | **0%** | **0** |

"Erro inesperado" = qualquer resposta fora das esperadas (201/409 no hold, 201/402 no checkout) ou timeout.

**Prova de integridade** ([`loadtest/proof.sql`](loadtest/proof.sql) + métricas do gateway) — rodada 1 / rodada 2:

| Checagem | pessimistic | optimistic | conditional | redis |
|---|---|---|---|---|
| Assento em mais de um item vendido | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Assento `SOLD` sem item vendido (ou o contrário) | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Pedidos duplicados para a mesma `Idempotency-Key` | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Ingressos válidos duplicados para o mesmo assento | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Cobranças aprovadas = pedidos confirmados | 676 = 676 / 682 = 682 | 672 = 672 / 680 = 680 | 677 = 677 / 666 = 666 | 634 = 634 / 658 = 658 |
| Eventos do outbox não publicados | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Assentos vendidos na medição (de 500) | 490 / 482 | 487 / 489 | 491 / 488 | 458 / 456 |

(As cobranças incluem as do aquecimento, que usa um evento separado; nenhuma precisou de estorno.)

### O que os números mostram

1. **A integridade não depende da estratégia:** nas 8 medições, zero venda dupla, zero cobrança duplicada e
   zero erro inesperado — o banco (UPDATE condicional + índice único parcial) é a última barreira em todas.
2. **O gargalo não é o lock, é o pool de conexões.** Com 1.000 requisições simultâneas e 10 conexões, a latência
   das estratégias de banco é quase toda *fila por conexão*. Virtual threads aceitam as 1.000 requisições, mas não
   criam conexões.
3. **O Redis foi o único resultado estável:** maior throughput em todas as rodadas (inclusive nas medições
   anteriores à revisão de segurança, 1.191 e 1.382 holds/s) e **p50 entre 4 e 12 ms**. O motivo é a rejeição
   antecipada: os ~92% que perdem a disputa recebem 409 por um `MGET` no Redis, *antes* de abrir a transação, sem
   pegar conexão. Na primeira versão, o portão Lua rodava dentro da transação e o Redis empatava com as outras
   (≈800 holds/s, p50 ≈ 800 ms). O Lua e o `UPDATE` condicional continuam sendo o portão de verdade.
4. **Entre as três estratégias de banco não há vencedor confiável nesta máquina:** rodadas idênticas variaram mais
   do que as estratégias entre si (`conditional` foi de 308 a 975 holds/s; `pessimistic` de 645 a 392). Escolher
   entre elas exigiria mais rodadas em hardware dedicado. Em teoria, o `conditional` faz menos trabalho (um único
   `UPDATE` que checa e grava), o `optimistic` desperdiça leitura + escrita + rollback quando perde, e o
   `pessimistic` segura a conexão enquanto espera o lock.
5. **O Redis vendeu menos assentos (≈457) sem vazar nenhum.** A medição termina tão rápido que os assentos
   devolvidos por pagamentos recusados voltam quando quase todos os compradores já foram atendidos. Conferido: os
   restantes ficam `AVAILABLE`, nenhum preso em `HELD`.
6. **Próximos passos para a cauda (p95/p99):** dimensionar o pool / PgBouncer, rate limiting ou fila virtual
   (sala de espera) antes da reserva.

## Como as garantias são asseguradas

**Nunca vender o mesmo assento duas vezes** — defesa em camadas:
1. a **estratégia** de reserva escolhe um único dono para o assento;
2. a confirmação só vende assentos que **ainda pertencem** ao hold (`UPDATE … WHERE hold_id = ? AND status = 'HELD'`);
3. o **índice único parcial** `ux_order_item_active_seat ON order_item(seat_id) WHERE active` faz o próprio
   banco recusar uma segunda venda, mesmo que as camadas acima tenham um bug.

**Nunca cobrar duas vezes:**
1. `UNIQUE(user_id, idempotency_key)` em `orders`: repetir o checkout devolve o mesmo pedido (`Idempotent-Replayed: true`);
2. o gateway é chamado com o **id do pedido** como chave de idempotência — retries e requisições concorrentes
   reaproveitam a mesma cobrança; o job de recuperação **só consulta** o gateway, nunca cria cobrança (pedido
   abandonado antes de pagar é encerrado sem cobrar; pedido cobrado antes de uma queda é confirmado);
3. se os assentos se perdem durante um pagamento lento, a cobrança é **estornada** e o pedido vira `PAYMENT_FAILED`
   (estorno só existe para cobrança aprovada, e é idempotente).

**Nunca perder o pós-venda:** o evento vai para a tabela `outbox_event` **na mesma transação** do pedido; um relay
publica com *publisher confirms*. Entrega *at-least-once* → consumidores idempotentes
(`UNIQUE(order_id, seat_id)` em `ticket` + `INSERT … ON CONFLICT DO NOTHING`).

## Segurança

Revisão guiada por OWASP Top 10 e OWASP API Security Top 10:

| Risco | O que o projeto faz |
|---|---|
| Acesso a objeto de outro usuário (API1 / A01) | Pedidos, holds, ingressos e QR respondem **404** para quem não é o dono — nem a existência vaza. IDs públicos são UUID v4 aleatórios. |
| Função administrativa exposta (API5) | `POST /events` exige `X-Admin-Token` (comparação em tempo constante; sem token configurado, a operação fica **desligada**). |
| Consumo irrestrito de recursos (API4) | Corpo de requisição limitado a 64 KB (413), evento limitado a 10.000 assentos, hold limitado a 4 assentos. |
| Injeção (A03) | Toda consulta é parametrizada (JPA, SQL nativo, JdbcTemplate, `KEYS/ARGV` no Lua). `X-User-Id` só aceita `[A-Za-z0-9_-]{1,64}` — sem markup nem sequências que forjem logs. |
| Configuração insegura (A05) | Postgres, Redis, RabbitMQ e app escutam só em `127.0.0.1`; Redis exige senha; credenciais vêm do ambiente (`.env.example`); Actuator expõe só `health` (status, sem versões/disco); headers `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy` e `Cache-Control: no-store` (respostas carregam códigos de ingresso). |
| Falhas de integridade de dados (A08) | Preço calculado no servidor (o cliente nunca envia valor); DTOs explícitos (sem *mass assignment*); código do ingresso com 144 bits de `SecureRandom`. |
| Componentes vulneráveis / cadeia de suprimentos (A06/A08) | Dependabot (Maven, Actions, Docker), Actions fixadas por SHA, token do CI só leitura, imagens fixadas por digest, Maven Wrapper com SHA-256. |
| Vazamento em erros (A05/A09) | Toda resposta de erro é ProblemDetail sem stack trace nem SQL; detalhes só no log. Container roda como usuário não-root. |

## Decisões técnicas

| Decisão | Por quê |
|---|---|
| Pagamento **fora** de qualquer transação | Uma chamada lenta não pode segurar conexão do pool nem lock de linha. O checkout são 3 transações curtas, todas repetíveis. |
| Expiração **lógica** + job de limpeza | `held_until <= agora` já conta como livre em todo lugar; o job só faz faxina. Se ele parar, nada quebra. |
| Transições de status com **UPDATE condicional** | `… WHERE status = 'ACTIVE'` + contagem de linhas: entre chamadas concorrentes, exatamente uma vence, sem lock explícito. |
| Hold `CONVERTED` no início do checkout | Impede que o usuário libere (ou o job expire) o hold durante o pagamento. |
| Rejeição antecipada **fora** da transação (Redis) | Numa abertura de vendas a maioria perde; quem perde sem pegar conexão do pool deixa o banco para quem ganhou. Foi o que dobrou o throughput no k6. |
| Estratégia escolhida numa **fábrica** (`enum` + `switch`) | Valor inválido derruba o startup com mensagem clara (antes: erro obscuro de "no qualifying bean", encontrado durante o teste de carga). |
| Redis como **portão**, Postgres como **verdade** | Se o Redis perder dados, o UPDATE condicional no banco ainda barra a venda dupla (há teste que apaga a chave e prova isso). |
| `TransactionSynchronization` para compensar o Redis | Se a transação do banco der rollback, o claim no Redis é desfeito; sem isso o assento ficaria bloqueado por 10 min. |
| Chaves Redis `seat:{eventId}:seatId` | *Hash tag* mantém os assentos de um evento no mesmo slot do Redis Cluster (scripts multi-chave exigem isso). |
| **Transactional Outbox** em vez de publicar direto | Publicar após o commit perde a mensagem se a app cair no meio; o outbox não. `FOR UPDATE SKIP LOCKED` permite várias instâncias. |
| DLQ por fila + `default-requeue-rejected: false` | Mensagem venenosa não fica em loop infinito; vai para a DLQ depois de 3 tentativas com backoff. |
| Evento Spring **síncrono** para anular ingressos no cancelamento | Mesma transação (pedido cancelado nunca fica com ingresso válido) sem criar dependência circular entre pacotes. |
| Sequences `INCREMENT BY 50` em vez de IDENTITY | Permite ao Hibernate fazer *JDBC batch*: um evento de 500 assentos é criado em ~0,4 s. |
| Testcontainers com Postgres real (não H2) | Locks, índices parciais, `SKIP LOCKED` e `ON CONFLICT` são comportamentos do Postgres. |
| `Clock` injetável (`MutableClock` nos testes) | Testes de expiração "pulam" 10 minutos sem `sleep`. |
| ProblemDetail (RFC 9457) | Erros padronizados, sem stack trace nem SQL na resposta; 401 token de admin, 402 recusa, 409 conflito, 413 corpo grande, 422 regra de negócio, 503 + `Retry-After` quando o resultado do pagamento é incerto ou uma dependência caiu. Documentados no OpenAPI. |

## API

Documentação interativa: **http://localhost:8081/swagger-ui.html**

| Método | Rota | Descrição |
|---|---|---|
| `POST` | `/events` | Cria evento com setores (preço por setor) e layout de assentos — exige `X-Admin-Token` |
| `GET` | `/events/{id}` | Evento e setores |
| `GET` | `/events/{id}/seats` | Mapa de assentos com disponibilidade atual |
| `POST` | `/events/{id}/holds` | Reserva temporária de 1 a 4 assentos (10 min) |
| `GET` | `/holds/{id}` | Consulta a reserva (status, prazo e assentos) |
| `DELETE` | `/holds/{id}` | Libera a reserva |
| `POST` | `/holds/{id}/checkout` | Paga a reserva (header `Idempotency-Key` obrigatório) |
| `GET` | `/orders/{id}` | Consulta pedido |
| `POST` | `/orders/{id}/cancel` | Cancela pedido confirmado (antes do evento): estorno + assentos voltam à venda |
| `GET` | `/orders/{id}/tickets` | Ingressos emitidos (assíncrono) |
| `GET` | `/tickets/{id}/qr` | QR code (PNG) do ingresso |

Todas as rotas de usuário exigem o header `X-User-Id` (`[A-Za-z0-9_-]{1,64}`). Vendas (hold e checkout) fecham quando o evento começa.

## Como rodar

Pré-requisitos: **Docker** (com Compose) e, para rodar fora do Docker, **Java 21** (o Maven Wrapper baixa o Maven).

```bash
docker compose up -d --build
```

Sem `.env`, o compose usa credenciais de desenvolvimento (aceitáveis porque tudo escuta só em `127.0.0.1`).
Para outros ambientes, copie [`.env.example`](.env.example) para `.env` e troque os valores.

| Serviço | Endereço |
|---|---|
| Demo visual | http://localhost:8081/ |
| API / Swagger | http://localhost:8081/swagger-ui.html |
| Health | http://localhost:8081/actuator/health |
| RabbitMQ (painel) | http://localhost:15673 (usuário/senha do `.env`; padrão ticket / ticket) |
| PostgreSQL | localhost:5433 |
| Redis | localhost:6380 |

As portas fogem das padrão (5432, 6379, 8080…) para conviver com outros projetos rodando na mesma máquina.

Exemplo de compra:

```bash
# 1. cria um evento com 2 fileiras x 5 assentos (token de admin: ADMIN_TOKEN do .env; padrão local-admin-token)
curl -s -X POST localhost:8081/events -H 'X-Admin-Token: local-admin-token' -H 'Content-Type: application/json' -d '{
  "name":"Show","venue":"Arena","startsAt":"2030-01-01T20:00:00Z",
  "sectors":[{"name":"Pista","price":150,"rows":2,"seatsPerRow":5}]}'

# 2. reserva os assentos 1 e 2 (ids vêm do GET /events/{id}/seats)
curl -s -X POST localhost:8081/events/1/holds -H 'X-User-Id: ana' \
  -H 'Content-Type: application/json' -d '{"seatIds":[1,2]}'

# 3. paga (repita o comando: mesmo pedido, nenhuma cobrança nova)
curl -s -X POST localhost:8081/holds/<HOLD_ID>/checkout -H 'X-User-Id: ana' -H 'Idempotency-Key: ana-compra-001'
```

Trocar a estratégia de concorrência:

```bash
STRATEGY=redis docker compose up -d app      # pessimistic | optimistic | conditional | redis
```

## Testes

```bash
./mvnw verify      # unitários + integração (Testcontainers sobe Postgres, Redis e RabbitMQ reais)
```

**97 testes, cobertura de 95% das linhas e 88% dos branches** (JaCoCo; relatório em `target/site/jacoco/index.html`).
O build **falha** se a cobertura cair abaixo de 90% das linhas ou 80% dos branches; no CI, o resumo aparece na
execução e o relatório completo fica como artefato.

Destaques:
- `ReservationStrategyContract` — o mesmo contrato de concorrência roda contra as 4 estratégias;
- `CheckoutConcurrencyTest` — 10 requisições simultâneas com a mesma `Idempotency-Key` → 1 pedido, 1 cobrança;
- `CheckoutPaymentFailureTest` — recusa, assento perdido durante pagamento lento (estorno), timeout do gateway (503 → retry), pedido abandonado encerrado sem cobrança, pedido cobrado antes de uma queda confirmado pelo job;
- `PostSaleFlowTest` — checkout → outbox → RabbitMQ → ingressos + QR → e-mail; reentrega idempotente; mensagem venenosa na DLQ;
- `OrderCancellationTest` — cancelamento, revenda, estorno único.

Teste de carga (apaga os dados locais deste projeto):

```bash
./loadtest/run-all.sh                         # 4 estratégias, 5.000 usuários, 1.000 simultâneos
STRATEGIES="redis" USERS=2000 VUS=500 ./loadtest/run-all.sh
```

## Limitações conhecidas e próximos passos

- **Autenticação:** `X-User-Id` é só um identificador de escopo, **não** autenticação, e o `X-Admin-Token` é um segredo compartilhado, não um controle de acesso completo. Produção: OAuth2/JWT (Spring Security resource server) com papéis.
- **Bots e cambistas:** sem rate limiting nem fila virtual, usuários falsos podem segurar assentos por 10 min sem pagar (OWASP API6). Próximo passo: rate limit por usuário/IP e sala de espera.
- **DLQ sem ferramenta de reprocessamento:** mensagens mortas ficam visíveis no painel do RabbitMQ, mas o reenvio é manual.
- **Gateway fake em memória:** esquece cobranças ao reiniciar; um gateway real guarda a idempotência do lado dele.
- **Outbox sem retenção:** linhas publicadas não são apagadas (falta um job de limpeza).
- **E-mail pode duplicar** em reentregas (at-least-once); resolveria com tabela de envios.
- **Disponibilidade "em tempo real"** é por consulta; *push* (SSE/WebSocket) é evolução natural.
- **Job de expiração** roda em todas as instâncias (inofensivo, UPDATEs condicionais); com muitas instâncias, ShedLock.
- **Carga medida numa máquina só:** app, banco, Redis, RabbitMQ e k6 disputam a mesma CPU — os números valem para **comparar** estratégias, não como capacidade absoluta.
