// Abertura de vendas: USERS compradores distintos (VUS ao mesmo tempo) disputam 500 assentos.
// Cada comprador quer 1 ou 2 assentos vizinhos escolhidos ao acaso: tenta o hold e, se ganhar, paga.
import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const RESULT_NAME = __ENV.RESULT_NAME || 'local';
const USERS = Number(__ENV.USERS || 5000);
const VUS = Number(__ENV.VUS || 1000);
const ROWS = 20;
const SEATS_PER_ROW = 25;

export const options = {
    scenarios: {
        rush: { executor: 'shared-iterations', vus: VUS, iterations: USERS, maxDuration: '10m' },
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    setupTimeout: '2m',
};

const holdDuration = new Trend('hold_duration', true);
const checkoutDuration = new Trend('checkout_duration', true);
const holdsWon = new Counter('holds_won');
const holdsRejected = new Counter('holds_rejected');
const purchases = new Counter('purchases');
const declined = new Counter('payments_declined');
const unexpected = new Counter('unexpected_errors');

const JSON_HEADERS = { 'Content-Type': 'application/json' };

export function setup() {
    const event = http.post(`${BASE_URL}/events`, JSON.stringify({
        name: `Load test (${RESULT_NAME})`,
        venue: 'Arena',
        startsAt: '2030-01-01T20:00:00Z',
        sectors: [{ name: 'Pista', price: 150.0, rows: ROWS, seatsPerRow: SEATS_PER_ROW }],
    }), { headers: JSON_HEADERS });
    if (event.status !== 201) {
        exec.test.abort(`could not create event: ${event.status} ${event.body}`);
    }
    const eventId = event.json('id');
    const seatIds = http.get(`${BASE_URL}/events/${eventId}/seats`).json('sectors.0.seats').map((seat) => seat.id);
    return { eventId, seatIds };
}

export default function (data) {
    // Prefixo por rodada: o aquecimento não pode compartilhar usuários (e Idempotency-Keys) com a medição
    const user = `${RESULT_NAME}-user-${exec.scenario.iterationInTest}`;
    const wanted = 1 + Math.floor(Math.random() * 2);
    const start = Math.floor(Math.random() * (data.seatIds.length - wanted + 1));
    const seatIds = data.seatIds.slice(start, start + wanted);

    const hold = http.post(`${BASE_URL}/events/${data.eventId}/holds`, JSON.stringify({ seatIds }), {
        headers: { ...JSON_HEADERS, 'X-User-Id': user },
        tags: { name: 'hold' },
        responseCallback: http.expectedStatuses(201, 409),
    });
    holdDuration.add(hold.timings.duration);

    if (hold.status === 409) {
        holdsRejected.add(1);
        return;
    }
    if (hold.status !== 201) {
        unexpected.add(1, { step: 'hold', status: String(hold.status) });
        return;
    }
    holdsWon.add(1);

    const checkout = http.post(`${BASE_URL}/holds/${hold.json('id')}/checkout`, null, {
        headers: { 'X-User-Id': user, 'Idempotency-Key': `${user}-checkout` },
        tags: { name: 'checkout' },
        responseCallback: http.expectedStatuses(201, 402),
    });
    checkoutDuration.add(checkout.timings.duration);

    if (checkout.status === 201) {
        purchases.add(1);
    } else if (checkout.status === 402) {
        declined.add(1);
    } else {
        unexpected.add(1, { step: 'checkout', status: String(checkout.status) });
    }
}

export function handleSummary(data) {
    const metric = (name, field) => (data.metrics[name] ? data.metrics[name].values[field] : 0);
    const seconds = data.state.testRunDurationMs / 1000;
    const holdRequests = metric('holds_won', 'count') + metric('holds_rejected', 'count');
    const totalRequests = metric('http_reqs', 'count');

    const result = {
        strategy: RESULT_NAME,
        users: USERS,
        concurrentUsers: VUS,
        durationSeconds: Number(seconds.toFixed(1)),
        holdThroughputPerSecond: Number((holdRequests / seconds).toFixed(1)),
        holdLatencyMs: {
            p50: metric('hold_duration', 'med'),
            p95: metric('hold_duration', 'p(95)'),
            p99: metric('hold_duration', 'p(99)'),
            max: metric('hold_duration', 'max'),
        },
        checkoutLatencyMs: {
            p95: metric('checkout_duration', 'p(95)'),
            p99: metric('checkout_duration', 'p(99)'),
        },
        holdsWon: metric('holds_won', 'count'),
        holdsRejected: metric('holds_rejected', 'count'),
        purchases: metric('purchases', 'count'),
        paymentsDeclined: metric('payments_declined', 'count'),
        unexpectedErrors: metric('unexpected_errors', 'count'),
        errorRatePercent: Number(((100 * metric('unexpected_errors', 'count')) / totalRequests).toFixed(3)),
    };

    return {
        stdout: `${JSON.stringify(result, null, 2)}\n`,
        [`/scripts/results/${RESULT_NAME}.json`]: JSON.stringify(result, null, 2),
    };
}
