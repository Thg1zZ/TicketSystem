#!/usr/bin/env bash
# Leitura em massa do mapa contra a stack já no ar (docker compose up -d --build).
# Mostra quantas leituras chegaram e quantas viraram consulta ao banco (seatmap_loads, somado nas réplicas).
# Uso: ./loadtest/spectators.sh
#      SPECTATORS=10000 DURATION=30s ./loadtest/spectators.sh
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p loadtest/results

loads() {
  for container in $(docker compose ps -q app); do
    docker exec "$container" curl -s http://localhost:8082/actuator/prometheus
  done | awk '/^seatmap_loads_total\{/ { sum += $NF } END { printf "%d\n", sum }'
}

name=${RESULT_NAME:-spectators-$(docker compose ps -q app | wc -l | tr -d ' ')r}
before=$(loads)
# O script é escolhido pelo compose (K6_SCRIPT): nenhum caminho passa pelo shell (o Git Bash os converteria)
K6_SCRIPT=spectators.js docker compose --profile loadtest run --rm --no-deps \
  -e RESULT_NAME="$name" -e SPECTATORS="${SPECTATORS:-5000}" -e DURATION="${DURATION:-60s}" k6 2>/dev/null
after=$(loads)
echo "Consultas ao banco para montar o mapa: $((after - before))" | tee "loadtest/results/$name-db.txt"
