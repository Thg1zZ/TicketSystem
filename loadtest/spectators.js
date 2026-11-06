// Leitura em massa: SPECTATORS pessoas olhando o mapa de um evento (cada uma recarrega a cada POLL_SECONDS),
// enquanto BUYERS compradores reservam e liberam assentos para o mapa mudar o tempo todo.
// Cada espectador manda If-None-Match com o último ETag, como um navegador faria.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || 'local-admin-token';
const RESULT_NAME = __ENV.RESULT_NAME || 'spectators';
const SPECTATORS = Number(__ENV.SPECTATORS || 5000);
const POLL_SECONDS = Number(__ENV.POLL_SECONDS || 2);
const BUYERS = Number(__ENV.BUYERS || 20);
const DURATION = __ENV.DURATION || '60s';

export const options = {
    scenarios: {
        // Taxa de chegada constante: 5.000 espectadores a cada 2 s = 2.500 leituras/s, sem precisar de 5.000 VUs
        spectators: {
            executor: 'constant-arrival-rate', exec: 'watch', rate: SPECTATORS / POLL_SECONDS, timeUnit: '1s',
            duration: DURATION, preAllocatedVUs: 300, maxVUs: 1500,
        },
        buyers: { executor: 'constant-vus', exec: 'buy', vus: BUYERS, duration: DURATION },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

const readDuration = new Trend('seatmap_read_duration', true);
const fullReads = new Counter('seatmap_full_reads');
const notModified = new Counter('seatmap_not_modified');
const unexpected = new Counter('unexpected_errors');

// Cada VU representa um espectador por vez e guarda o ETag da última leitura (como o cache do navegador)
let lastEtag = null;

export function setup() {
    const event = http.post(`${BASE_URL}/events`, JSON.stringify({
        name: `Spectators (${RESULT_NAME})`,
        venue: 'Arena',
        startsAt: '2030-01-01T20:00:00Z',
        sectors: [{ name: 'Pista', price: 150.0, rows: 20, seatsPerRow: 25 }],
    }), { headers: { 'Content-Type': 'application/json', 'X-Admin-Token': ADMIN_TOKEN } });
    if (event.status !== 201) {
        exec.test.abort(`could not create event: ${event.status} ${event.body}`);
    }
    const eventId = event.json('id');
    const seatIds = http.get(`${BASE_URL}/events/${eventId}/seats`).json('sectors.0.seats').map((seat) => seat.id);
    return { eventId, seatIds };
}

export function watch(data) {
    const headers = lastEtag ? { 'If-None-Match': lastEtag } : {};
    const response = http.get(`${BASE_URL}/events/${data.eventId}/seats`, {
        headers, tags: { name: 'seatmap' }, responseCallback: http.expectedStatuses(200, 304),
    });
    readDuration.add(response.timings.duration);
    if (response.status === 200) {
        fullReads.add(1);
        lastEtag = response.headers.Etag || response.headers.ETag || null;
    } else if (response.status === 304) {
        notModified.add(1);
    } else {
        unexpected.add(1);
    }
}

export function buy(data) {
    const user = `${RESULT_NAME}-buyer-${exec.vu.idInTest}`;
    const seatId = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];
    const hold = http.post(`${BASE_URL}/events/${data.eventId}/holds`, JSON.stringify({ seatIds: [seatId] }), {
        headers: { 'Content-Type': 'application/json', 'X-User-Id': user },
        tags: { name: 'hold' }, responseCallback: http.expectedStatuses(201, 409),
    });
    if (hold.status === 201) {
        sleep(0.5);
        http.del(`${BASE_URL}/holds/${hold.json('id')}`, null, {
            headers: { 'X-User-Id': user }, tags: { name: 'release' }, responseCallback: http.expectedStatuses(204),
        });
    } else if (hold.status !== 409) {
        unexpected.add(1);
    }
    sleep(0.5);
}

export function handleSummary(data) {
    const metric = (name, field) => (data.metrics[name] ? data.metrics[name].values[field] : 0);
    const seconds = data.state.testRunDurationMs / 1000;
    const reads = metric('seatmap_full_reads', 'count') + metric('seatmap_not_modified', 'count');
    const result = {
        name: RESULT_NAME,
        spectators: SPECTATORS,
        pollSeconds: POLL_SECONDS,
        durationSeconds: Number(seconds.toFixed(1)),
        seatMapReads: reads,
        seatMapReadsPerSecond: Number((reads / seconds).toFixed(1)),
        notModifiedPercent: Number(((100 * metric('seatmap_not_modified', 'count')) / reads).toFixed(1)),
        readLatencyMs: {
            p50: metric('seatmap_read_duration', 'med'),
            p95: metric('seatmap_read_duration', 'p(95)'),
            p99: metric('seatmap_read_duration', 'p(99)'),
        },
        droppedIterations: metric('dropped_iterations', 'count'),
        unexpectedErrors: metric('unexpected_errors', 'count'),
    };
    return {
        stdout: `${JSON.stringify(result, null, 2)}\n`,
        [`/scripts/results/${RESULT_NAME}.json`]: JSON.stringify(result, null, 2),
    };
}
