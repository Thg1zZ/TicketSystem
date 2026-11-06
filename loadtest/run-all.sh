#!/usr/bin/env bash
# Roda o teste de carga para cada estratégia, cada uma com banco, Redis e RabbitMQ zerados.
# ATENÇÃO: apaga os dados locais deste projeto (banco, Redis e RabbitMQ).
# Uso: ./loadtest/run-all.sh                                  (todas, 3 réplicas)
#      STRATEGIES="redis" APP_REPLICAS=1 ./loadtest/run-all.sh
# Prometheus/Grafana (perfil observability), se estiverem no ar, continuam rodando entre as rodadas.
set -euo pipefail
cd "$(dirname "$0")/.."

STRATEGIES=${STRATEGIES:-"pessimistic optimistic conditional redis"}
export APP_REPLICAS=${APP_REPLICAS:-3}
mkdir -p loadtest/results

reset_stack() {
  docker compose rm -sfv nginx app postgres redis rabbitmq >/dev/null 2>&1
  docker volume rm -f ticket-system_pgdata >/dev/null
}

# Pronto = todas as réplicas respondem /readyz e o nginx também
wait_for_app() {
  for _ in $(seq 1 90); do
    local total=0 ready=0
    for container in $(docker compose ps -q app); do
      total=$((total + 1))
      docker exec "$container" curl -sf http://localhost:8081/readyz >/dev/null && ready=$((ready + 1))
    done
    if [ "$total" -eq "$APP_REPLICAS" ] && [ "$ready" -eq "$total" ] && curl -sf http://localhost:8081/readyz >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "app did not become ready" >&2
  exit 1
}

# Soma o contador de todas as instâncias, lido na porta interna de gestão (8082, não publicada no host)
payments() {
  for container in $(docker compose ps -q app); do
    docker exec "$container" curl -s http://localhost:8082/actuator/prometheus
  done | awk -v outcome="outcome=\"$1\"" '/^payments_total\{/ && index($0, outcome) { sum += $NF } END { printf "%d\n", sum }'
}

for strategy in $STRATEGIES; do
  name="$strategy-${APP_REPLICAS}r"
  echo "=================== $strategy, $APP_REPLICAS réplica(s) ==================="
  reset_stack
  STRATEGY=$strategy docker compose up -d --build >/dev/null 2>&1
  wait_for_app
  docker compose logs app | grep -o "Seat reservation strategy: .*" | sort | uniq -c

  # --no-deps: o k6 não pode recriar o app no meio da medição
  # Aquecimento (JIT, pools, caches) num evento separado; resultado descartado
  RESULT_NAME="warmup-$name" USERS=1000 VUS=200 docker compose --profile loadtest run --rm --no-deps k6 >/dev/null 2>&1
  rm -f "loadtest/results/warmup-$name.json"

  RESULT_NAME=$name docker compose --profile loadtest run --rm --no-deps k6 2>/dev/null

  sleep 10 # deixa o outbox e os consumidores drenarem as filas
  docker compose exec -T postgres psql -U ticket -d tickets -q < loadtest/proof.sql | tee "loadtest/results/$name-proof.txt"
  {
    echo "== 7) Cobranças no gateway (aprovadas = confirmadas + estornadas)"
    echo "aprovadas=$(payments approved) recusadas=$(payments declined) estornadas=$(payments refunded)"
    echo "== 8) Memória por réplica: pico do cgroup e eventos (oom/max devem ser 0)"
    for container in $(docker compose ps -q app); do
      docker exec "$container" sh -c 'echo "$(hostname) pico=$(($(cat /sys/fs/cgroup/memory.peak) / 1048576))MiB $(grep -E "^(max|oom_kill) " /sys/fs/cgroup/memory.events | tr "\n" " ")"'
    done
  } | tee -a "loadtest/results/$name-proof.txt"
done
