#!/usr/bin/env bash
# Roda o teste de carga para cada estratégia, cada uma com banco, Redis e RabbitMQ zerados.
# ATENÇÃO: apaga os dados locais deste projeto (docker compose down -v).
# Uso: ./loadtest/run-all.sh            (todas)
#      STRATEGIES="redis" ./loadtest/run-all.sh
set -euo pipefail
cd "$(dirname "$0")/.."

STRATEGIES=${STRATEGIES:-"pessimistic optimistic conditional redis"}
mkdir -p loadtest/results

wait_for_app() {
  for _ in $(seq 1 60); do
    curl -sf http://localhost:8081/actuator/health >/dev/null && return 0
    sleep 2
  done
  echo "app did not become healthy" >&2
  exit 1
}

payments() {
  local value
  value=$(curl -s "http://localhost:8081/actuator/metrics/payments?tag=outcome:$1" | sed -n 's/.*"value":\([0-9.]*\).*/\1/p')
  echo "${value:-0}"
}

for strategy in $STRATEGIES; do
  echo "=================== $strategy ==================="
  docker compose down -v --remove-orphans >/dev/null 2>&1
  STRATEGY=$strategy docker compose up -d --build >/dev/null 2>&1
  wait_for_app
  docker compose logs app | grep -o "Seat reservation strategy: .*"

  # --no-deps: o k6 não pode recriar o app no meio da medição
  # Aquecimento (JIT, pools, caches) num evento separado; resultado descartado
  RESULT_NAME="warmup-$strategy" USERS=1000 VUS=200 docker compose --profile loadtest run --rm --no-deps k6 >/dev/null 2>&1
  rm -f "loadtest/results/warmup-$strategy.json"

  RESULT_NAME=$strategy docker compose --profile loadtest run --rm --no-deps k6 2>/dev/null

  sleep 10 # deixa o outbox e os consumidores drenarem as filas
  docker compose exec -T postgres psql -U ticket -d tickets -q < loadtest/proof.sql | tee "loadtest/results/$strategy-proof.txt"
  {
    echo "== 7) Cobranças no gateway (aprovadas = confirmadas + estornadas)"
    echo "aprovadas=$(payments approved) recusadas=$(payments declined) estornadas=$(payments refunded)"
  } | tee -a "loadtest/results/$strategy-proof.txt"
done
