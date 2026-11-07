#!/usr/bin/env bash
# Engenharia de caos na stack real: durante o rush, derruba o Redis e o RabbitMQ, religa depois e prova que
# nenhum assento foi vendido duas vezes e nenhuma venda se perdeu.
# ATENÇÃO: apaga os dados locais deste projeto (banco, Redis e RabbitMQ).
# Uso: ./loadtest/chaos.sh                 (estratégia redis, a que mais depende do Redis)
set -euo pipefail
cd "$(dirname "$0")/.."

export STRATEGY=${STRATEGY:-redis}
export APP_REPLICAS=${APP_REPLICAS:-3}
export RATE_LIMIT_ENABLED=false # todo o k6 sai de um IP só
name="chaos-$STRATEGY-${APP_REPLICAS}r"
mkdir -p loadtest/results

ready() {
  for _ in $(seq 1 90); do
    local total=0 up=0
    for container in $(docker compose ps -q app); do
      total=$((total + 1))
      docker exec "$container" curl -sf http://localhost:8081/readyz >/dev/null && up=$((up + 1))
    done
    [ "$total" -eq "$APP_REPLICAS" ] && [ "$up" -eq "$total" ] && return 0
    sleep 2
  done
  echo "app did not become ready" >&2
  exit 1
}

counter() { # soma um contador Prometheus em todas as réplicas: counter <nome> <filtro de rótulo>
  for container in $(docker compose ps -q app); do
    docker exec "$container" curl -s http://localhost:8082/actuator/prometheus
  done | awk -v name="^$1[{]" -v label="$2" '$0 ~ name && index($0, label) { sum += $NF } END { printf "%d\n", sum }'
}

docker compose rm -sfv nginx app postgres redis rabbitmq >/dev/null 2>&1
docker volume rm -f ticket-system_pgdata >/dev/null
docker compose up -d --build >/dev/null 2>&1
ready
RESULT_NAME="warmup-$name" USERS=1000 VUS=200 docker compose --profile loadtest run --rm --no-deps k6 >/dev/null 2>&1
rm -f "loadtest/results/warmup-$name.json"

echo "== rush começa; em 1 s o Redis cai, em 3 s o RabbitMQ cai"
RESULT_NAME=$name docker compose --profile loadtest run --rm --no-deps k6 > "loadtest/results/$name-k6.txt" 2>/dev/null &
k6=$!
sleep 1 && docker compose stop redis >/dev/null 2>&1 && echo "   Redis fora"
sleep 2 && docker compose stop rabbitmq >/dev/null 2>&1 && echo "   RabbitMQ fora"
wait $k6
cat "loadtest/results/$name-k6.txt"
echo "== readiness durante a queda (só depende do banco): $(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/readyz)"

echo "== religando Redis e RabbitMQ"
docker compose start redis rabbitmq >/dev/null 2>&1
sleep 30 # consumidores reconectam, o outbox publica o que ficou guardado
{
  docker compose exec -T postgres psql -U ticket -d tickets -q < loadtest/proof.sql
  echo "== 7) Cobranças no gateway (aprovadas = confirmadas + estornadas)"
  echo "aprovadas=$(counter payments_total 'outcome="approved"') estornadas=$(counter payments_total 'outcome="refunded"')"
  echo "== 8) Holds que seguiram só pelo banco porque o Redis estava fora"
  echo "fallbacks seat-claim=$(counter redis_fallback_total 'operation="seat-claim"') seat-filter=$(counter redis_fallback_total 'operation="seat-filter"')"
} | tee "loadtest/results/$name-proof.txt"
