#!/usr/bin/env bash
# Prova final: réplicas {1, 3} × estratégia {conditional, redis} × fila {sem, com}, mais o cenário de espectadores,
# repetido em ROUNDS rodadas (numa máquina só a variação entre rodadas é grande; uma rodada não prova nada).
# Resultados em loadtest/results/round-N/. ATENÇÃO: apaga os dados locais deste projeto.
# Uso: ./loadtest/matrix.sh        (2 rodadas, ~40 min)
set -euo pipefail
cd "$(dirname "$0")/.."

ROUNDS=${ROUNDS:-2}
for round in $(seq 1 "$ROUNDS"); do
  echo "################ rodada $round de $ROUNDS ################"
  for replicas in 1 3; do
    STRATEGIES="conditional redis" APP_REPLICAS=$replicas QUEUE_ENABLED=false ./loadtest/run-all.sh
    STRATEGIES="conditional redis" APP_REPLICAS=$replicas QUEUE_ENABLED=true ./loadtest/run-all.sh
    ./loadtest/spectators.sh # contra a stack que ficou no ar (redis, com fila)
  done
  mkdir -p "loadtest/results/round-$round"
  mv loadtest/results/*.json loadtest/results/*.txt "loadtest/results/round-$round/"
done
