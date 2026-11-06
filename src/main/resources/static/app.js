// Demo page for the Ticket System API. It uses only the public API, like any other client.
// Every text that comes from the API is inserted with textContent / text nodes, never innerHTML (no XSS).

const POLL_SEATS_MS = 2000;
const POLL_TICKETS_MS = 1000;
const HEALTH_MS = 10000;
const HOLD_TTL_MS = 10 * 60 * 1000;
const MAX_SEATS = 4;
const USER_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;

const brl = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const dateTime = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'long', timeStyle: 'short' });
const STATUS_LABEL = { available: 'livre', selected: 'selecionado', mine: 'seu', held: 'reservado', sold: 'vendido' };
const ORDER_BADGE = {
    CONFIRMED: ['Confirmado', 'ok'], PENDING_PAYMENT: ['Aguardando pagamento', 'warn'],
    PAYMENT_FAILED: ['Pagamento recusado', 'danger'], CANCELLED: ['Cancelado', 'danger'],
};

const $ = (id) => document.getElementById(id);

// Per-tab state lives in sessionStorage so two tabs can be two different buyers. Storage may be unavailable
// (private mode, blocked site data); the page keeps working without it.
const store = {
    get(area, key) {
        try { return window[area].getItem(key); } catch { return null; }
    },
    set(area, key, value) {
        try {
            if (value == null) window[area].removeItem(key); else window[area].setItem(key, value);
        } catch { /* storage unavailable: nothing to persist */ }
    },
};

const state = {
    userId: null,
    eventId: null,
    seats: new Map(),
    buttons: new Map(),
    layoutKey: null,
    selected: new Set(),
    hold: null,
    order: null,
    tickets: [],
    qrUrls: new Map(),
};

class ApiError extends Error {
    constructor(status, message, problem) {
        super(message);
        this.status = status;
        this.problem = problem;
    }
}

function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (key === 'class') node.className = value;
        else if (key === 'dataset') Object.assign(node.dataset, value);
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
        else if (value !== false && value != null) node.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
        if (child != null) node.append(child instanceof Node ? child : String(child));
    }
    return node;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function parseJson(text) {
    try { return JSON.parse(text); } catch { return null; }
}

// ---------- API ----------

async function api(method, path, { headers = {}, body, asUser = true, log = true } = {}) {
    const init = { method, headers: { ...headers } };
    if (asUser) init.headers['X-User-Id'] = state.userId;
    if (body !== undefined) {
        init.headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(body);
    }
    let response;
    try {
        response = await fetch(path, init);
    } catch {
        if (log) logCall(method, path, 0, 'sem conexão com a API');
        throw new ApiError(0, 'Sem conexão com a API.');
    }
    const isJson = (response.headers.get('content-type') || '').includes('json');
    const data = isJson ? await response.json() : null;
    if (log) logCall(method, path, response.status, summarize(response, data));
    if (!response.ok) throw new ApiError(response.status, problemMessage(data, response.status), data);
    return { data, headers: response.headers };
}

function problemMessage(problem, status) {
    if (problem?.errors) {
        return Object.entries(problem.errors).map(([field, message]) => `${field}: ${message}`).join(' · ');
    }
    return problem?.detail || problem?.title || `HTTP ${status}`;
}

function summarize(response, data) {
    if (!response.ok) return problemMessage(data, response.status);
    if (response.headers.get('Idempotent-Replayed') === 'true') return 'repetição: mesmo pedido (Idempotent-Replayed)';
    if (data?.status) return `status ${data.status}`;
    if (Array.isArray(data)) return `${data.length} item(s)`;
    return '';
}

// ---------- Feedback ----------

function logCall(method, path, status, detail) {
    const statusClass = `status s${String(status).charAt(0)}`;
    const time = new Date().toLocaleTimeString('pt-BR');
    const entry = el('li', {},
        el('span', { class: statusClass }, status || 'ERR'),
        el('span', { class: 'call', title: `${method} ${path}` }, `${method} ${path}`),
        el('span', { class: 'detail' }, detail ? `${time} · ${detail}` : time));
    const log = $('log');
    log.prepend(entry);
    while (log.children.length > 30) log.lastElementChild.remove();
}

function toast(message, kind = 'info') {
    const node = el('div', { class: `toast ${kind}` }, message);
    $('toasts').append(node);
    setTimeout(() => node.remove(), 5500);
}

async function busy(button, action) {
    button.classList.add('busy');
    try {
        await action();
    } finally {
        button.classList.remove('busy');
        render();
    }
}

// ---------- User ----------

function initUser() {
    const saved = store.get('sessionStorage', 'ts.user');
    state.userId = saved && USER_PATTERN.test(saved) ? saved : `comprador-${Math.random().toString(36).slice(2, 6)}`;
    store.set('sessionStorage', 'ts.user', state.userId);

    const input = $('user-id');
    input.value = state.userId;
    input.addEventListener('change', () => {
        const value = input.value.trim();
        if (!USER_PATTERN.test(value)) {
            toast('Comprador: use de 1 a 64 letras, números, "-" ou "_".', 'error');
            input.value = state.userId;
            return;
        }
        if (value === state.userId) return;
        state.userId = value;
        store.set('sessionStorage', 'ts.user', value);
        resetPurchase();
        toast(`Agora você compra como "${value}".`);
    });
}

// ---------- Event and seat map ----------

async function loadEvent(eventId) {
    try {
        const { data } = await api('GET', `/events/${eventId}`, { asUser: false });
        state.eventId = data.id;
        state.layoutKey = null;
        state.selected.clear();
        store.set('localStorage', 'ts.event', String(data.id));
        $('event-id').value = data.id;
        $('event-title').textContent = data.name;
        const sectors = data.sectors.map((sector) => `${sector.name} ${brl.format(sector.price)}`).join(' · ');
        $('event-meta').textContent = `${data.venue} · ${dateTime.format(new Date(data.startsAt))} · ${sectors}`;
        await refreshSeats({ log: true });
        return true;
    } catch (error) {
        if (error.status === 404) store.set('localStorage', 'ts.event', null);
        toast(error.status === 404 ? `Evento #${eventId} não existe.` : error.message, 'error');
        return false;
    }
}

async function refreshSeats({ log = false } = {}) {
    if (!state.eventId) return;
    try {
        const { data } = await api('GET', `/events/${state.eventId}/seats`, { asUser: false, log });
        applySeatMap(data);
    } catch (error) {
        if (log) toast(error.message, 'error');
    }
}

function applySeatMap(map) {
    state.seats.clear();
    for (const sector of map.sectors) {
        for (const seat of sector.seats) state.seats.set(seat.id, { ...seat, sector });
    }
    const layoutKey = map.sectors.map((sector) => `${sector.sectorId}:${sector.seats.length}`).join('|');
    if (layoutKey !== state.layoutKey) {
        buildSeatMap(map);
        state.layoutKey = layoutKey;
    }
    for (const sector of map.sectors) {
        const count = $(`sector-count-${sector.sectorId}`);
        if (count) count.textContent = `${sector.available} livres de ${sector.seats.length}`;
    }
    dropSelectionsTakenByOthers();
    render();
}

/**
 * Built once per layout; later refreshes only repaint classes, so keyboard focus survives the polling.
 */
function buildSeatMap(map) {
    state.buttons.clear();
    const sectors = map.sectors.map((sector) => {
        const rows = new Map();
        for (const seat of sector.seats) {
            if (!rows.has(seat.row)) rows.set(seat.row, []);
            rows.get(seat.row).push(seat);
        }
        const rowNodes = [...rows].map(([row, seats]) => el('div', { class: 'row' },
            el('span', { class: 'row-label', 'aria-hidden': 'true' }, row),
            seats.map((seat) => {
                const button = el('button', { class: 'seat', type: 'button', onclick: () => toggleSeat(seat.id) }, seat.number);
                state.buttons.set(seat.id, button);
                return button;
            }),
            el('span', { class: 'row-label', 'aria-hidden': 'true' }, row)));
        return el('div', { class: 'sector' },
            el('div', { class: 'sector-head' },
                el('strong', {}, sector.name),
                el('span', {}, brl.format(sector.price)),
                el('span', { id: `sector-count-${sector.sectorId}` })),
            el('div', { class: 'rows', role: 'group', 'aria-label': `Setor ${sector.name}` }, rowNodes));
    });
    $('seatmap').replaceChildren(el('div', { class: 'stage', 'aria-hidden': 'true' }, 'PALCO'), ...sectors);
}

function seatLabel(seatId) {
    const seat = state.seats.get(seatId);
    return seat ? `${seat.row}${seat.number}` : `#${seatId}`;
}

function mySeatIds() {
    const mine = new Set();
    state.hold?.seats.forEach((seat) => mine.add(seat.id));
    if (state.order?.status === 'CONFIRMED') state.order.items.forEach((item) => mine.add(item.seatId));
    return mine;
}

function paintSeats() {
    const mine = mySeatIds();
    for (const [seatId, button] of state.buttons) {
        const seat = state.seats.get(seatId);
        if (!seat) continue;
        let kind = 'available';
        if (mine.has(seatId) && seat.status !== 'AVAILABLE') kind = 'mine';
        else if (seat.status === 'HELD') kind = 'held';
        else if (seat.status === 'SOLD') kind = 'sold';
        const selected = kind === 'available' && state.selected.has(seatId);
        const look = selected ? 'selected' : kind;
        const flashing = button.classList.contains('flash');
        button.className = `seat ${look}`;
        button.classList.toggle('flash', flashing);
        button.disabled = kind !== 'available';
        button.setAttribute('aria-pressed', String(selected));
        button.setAttribute('aria-label', `Fileira ${seat.row}, assento ${seat.number}, ${STATUS_LABEL[look]}`);
    }
}

function flashSeat(seatId) {
    const button = state.buttons.get(seatId);
    if (!button) return;
    button.classList.add('flash');
    setTimeout(() => button.classList.remove('flash'), 1500);
}

function dropSelectionsTakenByOthers() {
    const lost = [...state.selected].filter((seatId) => state.seats.get(seatId)?.status !== 'AVAILABLE');
    if (lost.length === 0) return;
    lost.forEach((seatId) => {
        state.selected.delete(seatId);
        flashSeat(seatId);
    });
    toast(`Outra pessoa reservou ${lost.map(seatLabel).join(', ')} antes de você.`, 'warn');
}

function toggleSeat(seatId) {
    if (state.hold) {
        toast('Você já tem uma reserva ativa: pague ou libere antes de escolher outros assentos.', 'warn');
        return;
    }
    if (state.selected.has(seatId)) {
        state.selected.delete(seatId);
    } else if (state.selected.size >= MAX_SEATS) {
        toast(`No máximo ${MAX_SEATS} assentos por reserva.`, 'warn');
        return;
    } else {
        state.selected.add(seatId);
    }
    render();
}

// ---------- Hold ----------

async function reserve() {
    const seatIds = [...state.selected];
    await busy($('hold-button'), async () => {
        try {
            const { data } = await api('POST', `/events/${state.eventId}/holds`, { body: { seatIds } });
            state.selected.clear();
            setHold(data, crypto.randomUUID());
            toast(`${data.seats.map((seat) => `${seat.row}${seat.number}`).join(', ')} reservados por 10 minutos.`, 'ok');
        } catch (error) {
            const lost = error.problem?.unavailableSeatIds ?? [];
            lost.forEach((seatId) => {
                state.selected.delete(seatId);
                flashSeat(seatId);
            });
            if (lost.length) {
                toast(`Alguém foi mais rápido: ${lost.map(seatLabel).join(', ')} já foi reservado (409).`, 'error');
            } else if (error.problem?.holdId) {
                await restoreHold(error.problem.holdId);
                toast('Você já tinha uma reserva ativa neste evento; ela foi recuperada.', 'warn');
            } else {
                toast(error.message, 'error');
            }
        }
        await refreshSeats();
    });
}

function setHold(hold, idempotencyKey) {
    state.hold = { ...hold, idempotencyKey };
    store.set('sessionStorage', 'ts.hold', JSON.stringify({ id: hold.id, key: idempotencyKey }));
}

function clearHold() {
    state.hold = null;
    store.set('sessionStorage', 'ts.hold', null);
}

async function restoreHold(holdId, idempotencyKey = crypto.randomUUID()) {
    try {
        const { data } = await api('GET', `/holds/${holdId}`, { log: false });
        if (data.status === 'ACTIVE' && new Date(data.expiresAt) > new Date()) setHold(data, idempotencyKey);
        else clearHold();
    } catch {
        clearHold();
    }
}

async function release() {
    await busy($('release-button'), async () => {
        try {
            await api('DELETE', `/holds/${state.hold.id}`);
            toast('Reserva liberada; os assentos voltaram à venda.');
        } catch (error) {
            toast(error.message, 'error');
        }
        clearHold();
        await refreshSeats();
    });
}

function tickCountdown() {
    if (!state.hold) return;
    const remaining = new Date(state.hold.expiresAt) - Date.now();
    if (remaining <= 0) {
        clearHold();
        toast('Sua reserva expirou e os assentos voltaram à venda.', 'warn');
        refreshSeats();
        render();
        return;
    }
    const seconds = Math.ceil(remaining / 1000);
    const countdown = $('countdown');
    countdown.textContent = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
    countdown.classList.toggle('urgent', seconds <= 60);
    $('countdown-bar').style.width = `${Math.min(100, (remaining / HOLD_TTL_MS) * 100)}%`;
}

// ---------- Checkout ----------

async function pay() {
    const hold = state.hold;
    await busy($('pay-button'), async () => {
        try {
            const { data, headers } = await api('POST', `/holds/${hold.id}/checkout`, {
                headers: { 'Idempotency-Key': hold.idempotencyKey },
            });
            clearHold();
            setOrder(data);
            toast(headers.get('Idempotent-Replayed') === 'true'
                ? 'Este pagamento já tinha sido feito: mesmo pedido, nenhuma cobrança nova.'
                : 'Pagamento aprovado! Emitindo seus ingressos…', 'ok');
        } catch (error) {
            if (error.status === 503) {
                toast(`${error.message} Clique em Pagar de novo: a mesma chave garante uma única cobrança.`, 'warn');
                return;
            }
            if ([402, 404, 409, 422].includes(error.status)) clearHold();
            toast(error.status === 402
                ? `Pagamento recusado: ${error.message}. Os assentos voltaram à venda.`
                : error.message, 'error');
        }
        await refreshSeats();
    });
}

// ---------- Order and tickets ----------

function setOrder(order) {
    state.order = order;
    store.set('sessionStorage', 'ts.order', order.id);
    pollTickets(order.id);
}

function clearOrder() {
    state.order = null;
    state.tickets = [];
    state.qrUrls.forEach((url) => URL.revokeObjectURL(url));
    state.qrUrls.clear();
    store.set('sessionStorage', 'ts.order', null);
}

async function pollTickets(orderId) {
    for (let attempt = 0; attempt < 20; attempt++) {
        if (state.order?.id !== orderId) return;
        try {
            const { data } = await api('GET', `/orders/${orderId}/tickets`, { log: attempt === 0 });
            state.tickets = data;
            await loadQrCodes();
            render();
            const expected = state.order.status === 'CONFIRMED' ? state.order.items.length : data.length;
            if (data.length >= expected) {
                if (attempt > 0) logCall('GET', `/orders/${orderId}/tickets`, 200, `${data.length} ingresso(s) emitido(s)`);
                return;
            }
        } catch {
            return;
        }
        await sleep(POLL_TICKETS_MS);
    }
}

async function loadQrCodes() {
    for (const ticket of state.tickets) {
        if (ticket.status !== 'VALID' || state.qrUrls.has(ticket.id)) continue;
        const response = await fetch(`/tickets/${ticket.id}/qr`, { headers: { 'X-User-Id': state.userId } });
        if (response.ok) state.qrUrls.set(ticket.id, URL.createObjectURL(await response.blob()));
    }
}

async function cancelOrder() {
    await busy($('cancel-button'), async () => {
        try {
            const { data } = await api('POST', `/orders/${state.order.id}/cancel`);
            state.order = data;
            const { data: tickets } = await api('GET', `/orders/${data.id}/tickets`, { log: false });
            state.tickets = tickets;
            toast('Pedido cancelado e estornado; os assentos voltaram à venda.', 'ok');
        } catch (error) {
            toast(error.message, 'error');
        }
        await refreshSeats();
    });
}

async function restoreOrder(orderId) {
    try {
        const { data } = await api('GET', `/orders/${orderId}`, { log: false });
        setOrder(data);
    } catch {
        clearOrder();
    }
}

function resetPurchase() {
    clearHold();
    clearOrder();
    state.selected.clear();
    render();
}

// ---------- Rendering ----------

function render() {
    paintSeats();
    renderSelection();
    renderHold();
    renderOrder();
}

function renderSelection() {
    const seats = [...state.selected].map((seatId) => state.seats.get(seatId)).filter(Boolean);
    $('selection-list').replaceChildren(...seats.map((seat) => el('li', {}, `${seat.row}${seat.number}`)));
    const total = seats.reduce((sum, seat) => sum + Number(seat.sector.price), 0);
    $('selection-total').textContent = seats.length ? brl.format(total) : '';
    $('selection-hint').textContent = state.hold
        ? 'Você tem uma reserva ativa abaixo.'
        : !state.eventId ? 'Carregue ou crie um evento para começar.'
            : seats.length ? `${seats.length} de ${MAX_SEATS} assentos.` : `Clique em até ${MAX_SEATS} assentos livres.`;
    $('hold-button').disabled = seats.length === 0 || Boolean(state.hold);
}

function renderHold() {
    const hold = state.hold;
    $('hold-card').hidden = !hold;
    if (!hold) return;
    $('hold-seats').replaceChildren(...hold.seats.map((seat) => el('li', {}, `${seat.row}${seat.number} · ${seat.sector}`)));
    $('pay-total').textContent = brl.format(hold.total);
    $('idempotency-key').textContent = hold.idempotencyKey;
    tickCountdown();
}

function renderOrder() {
    const order = state.order;
    $('order-card').hidden = !order;
    if (!order) return;
    const [label, tone] = ORDER_BADGE[order.status] ?? [order.status, ''];
    const badge = $('order-status');
    badge.textContent = label;
    badge.className = `badge ${tone}`;
    $('order-meta').textContent = `${order.items.length} assento(s) · ${brl.format(order.total)}`
        + (order.paymentReference ? ` · ${order.paymentReference}` : '');
    $('cancel-button').hidden = order.status !== 'CONFIRMED';

    const tickets = state.tickets.map((ticket) => {
        const cancelled = ticket.status !== 'VALID';
        const qr = state.qrUrls.get(ticket.id);
        return el('div', { class: `ticket${cancelled ? ' cancelled' : ''}` },
            qr ? el('img', { src: qr, alt: `QR code do ingresso ${seatLabel(ticket.seatId)}` }) : null,
            el('strong', {}, `Assento ${seatLabel(ticket.seatId)}`),
            cancelled ? el('span', { class: 'void' }, 'CANCELADO') : null,
            el('code', {}, ticket.code));
    });
    const waiting = order.status === 'CONFIRMED' && state.tickets.length < order.items.length;
    $('tickets').replaceChildren(...tickets,
        waiting ? el('div', { class: 'waiting' }, el('span', { class: 'spinner' }), 'Emitindo ingressos via RabbitMQ…') : '');
}

// ---------- Health ----------

async function checkHealth() {
    const health = $('health');
    try {
        const { data } = await api('GET', '/readyz', { asUser: false, log: false });
        health.dataset.state = data.status === 'UP' ? 'up' : 'down';
        $('health-text').textContent = data.status === 'UP' ? 'API online' : `API ${data.status}`;
    } catch {
        health.dataset.state = 'down';
        $('health-text').textContent = 'API indisponível';
    }
}

// ---------- Create event dialog ----------

function initDialog() {
    const dialog = $('create-dialog');
    $('open-create').addEventListener('click', () => dialog.showModal());
    $('close-create').addEventListener('click', () => dialog.close());
    $('create-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        const form = event.target;
        const fields = new FormData(form);
        const submit = form.querySelector('[type="submit"]');
        await busy(submit, async () => {
            try {
                const { data } = await api('POST', '/events', {
                    asUser: false,
                    headers: { 'X-Admin-Token': fields.get('adminToken') },
                    body: {
                        name: fields.get('name'),
                        venue: fields.get('venue'),
                        startsAt: new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString(),
                        sectors: [{
                            name: 'Pista',
                            price: Number(fields.get('price')),
                            rows: Number(fields.get('rows')),
                            seatsPerRow: Number(fields.get('seatsPerRow')),
                        }],
                    },
                });
                form.elements.adminToken.value = '';
                dialog.close();
                toast(`Evento #${data.id} criado.`, 'ok');
                resetPurchase();
                await loadEvent(data.id);
            } catch (error) {
                toast(error.status === 401 ? 'Token de admin inválido.' : error.message, 'error');
            }
        });
    });
}

// ---------- Startup ----------

async function init() {
    initUser();
    initDialog();
    $('hold-button').addEventListener('click', reserve);
    $('pay-button').addEventListener('click', pay);
    $('release-button').addEventListener('click', release);
    $('cancel-button').addEventListener('click', cancelOrder);
    $('new-purchase').addEventListener('click', () => {
        clearOrder();
        render();
    });
    $('load-form').addEventListener('submit', (event) => {
        event.preventDefault();
        const eventId = Number($('event-id').value);
        if (eventId > 0) {
            resetPurchase();
            loadEvent(eventId);
        }
    });

    checkHealth();
    setInterval(checkHealth, HEALTH_MS);
    setInterval(tickCountdown, 1000);
    setInterval(() => {
        if (document.visibilityState === 'visible') refreshSeats();
    }, POLL_SEATS_MS);
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') refreshSeats();
    });

    const savedEvent = Number(store.get('localStorage', 'ts.event'));
    if (savedEvent > 0 && await loadEvent(savedEvent)) {
        const savedHold = parseJson(store.get('sessionStorage', 'ts.hold'));
        if (savedHold?.id) await restoreHold(savedHold.id, savedHold.key);
        const savedOrder = store.get('sessionStorage', 'ts.order');
        if (savedOrder) await restoreOrder(savedOrder);
    }
    render();
}

init();
