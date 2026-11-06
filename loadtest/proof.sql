-- Prova de integridade depois do teste de carga. Todas as checagens "deve ser 0" precisam dar 0.

\echo '== 1) Assentos presentes em mais de um item vendido (deve ser 0 linhas)'
SELECT seat_id, count(*) AS vendas
FROM order_item
WHERE active
GROUP BY seat_id
HAVING count(*) > 1;

\echo '== 2) Divergências entre assento SOLD e item vendido (deve ser 0)'
SELECT count(*) AS divergencias
FROM seat s
WHERE (s.status = 'SOLD') <> EXISTS (SELECT 1 FROM order_item i WHERE i.seat_id = s.id AND i.active);

\echo '== 3) Pedidos duplicados para o mesmo (usuário, Idempotency-Key) (deve ser 0 linhas)'
SELECT user_id, idempotency_key, count(*)
FROM orders
GROUP BY user_id, idempotency_key
HAVING count(*) > 1;

\echo '== 4) Ingressos válidos emitidos em dobro para o mesmo assento (deve ser 0 linhas)'
SELECT seat_id, count(*)
FROM ticket
WHERE status = 'VALID'
GROUP BY seat_id
HAVING count(*) > 1;

\echo '== 5) Resumo por evento (o aquecimento usa um evento separado)'
SELECT e.name                                                                        AS evento,
       (SELECT count(*) FROM seat s JOIN sector sc ON sc.id = s.sector_id
         WHERE sc.event_id = e.id AND s.status = 'SOLD')                             AS assentos_vendidos,
       count(o.id) FILTER (WHERE o.status = 'CONFIRMED')                             AS pedidos_confirmados,
       count(o.id) FILTER (WHERE o.status = 'PAYMENT_FAILED')                        AS pedidos_recusados,
       count(o.id) FILTER (WHERE o.status = 'PENDING_PAYMENT')                       AS pedidos_pendentes,
       (SELECT count(*) FROM ticket t JOIN orders o2 ON o2.id = t.order_id JOIN seat_hold h2 ON h2.id = o2.hold_id
         WHERE h2.event_id = e.id AND t.status = 'VALID')                            AS ingressos_emitidos
FROM event e
LEFT JOIN seat_hold h ON h.event_id = e.id
LEFT JOIN orders o ON o.hold_id = h.id
GROUP BY e.id, e.name
ORDER BY e.id;

\echo '== 6) Eventos do outbox ainda não publicados (deve ser 0)'
SELECT count(*) AS nao_publicados FROM outbox_event WHERE published_at IS NULL;
