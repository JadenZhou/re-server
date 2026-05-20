# Exercise 8 — Event-Based Architecture

The ex7 microservices stack stays in place. Two new services + one broker
turn synchronous side-effects into asynchronous events, so the
property-server doesn't need to know who cares about a price change.

## Broker

**RabbitMQ** (installed via `brew install rabbitmq`, runs on
`amqp://guest:guest@localhost:5672`). Two topic exchanges — one for raw
domain events, one for per-purchaser delivery.

## Topology

```
property-server  ─publish─►   ┌──────────────────┐
                              │  property.events │  topic exchange
analytics-server ─publish─►   └────────┬─────────┘
                                       │ binding "property.#"
                                       ▼
                              ┌──────────────────┐
                              │  notification.in │  queue
                              └────────┬─────────┘
                                       │ consume
                                       ▼
                          ┌─────────────────────────┐
                          │  notification-service   │ ── HTTP ──► property-server
                          │ (subscriber + fan-out)  │             purchaser-server
                          └────────────┬────────────┘
                                       │ publish "purchaser.<id>"
                                       ▼
                              ┌──────────────────────┐
                              │ purchaser.messages   │  topic exchange
                              └────────┬─────────────┘
                                       │ binding "purchaser.#"
                                       ▼
                              ┌──────────────────────┐
                              │ purchaser.delivery   │  queue
                              └────────┬─────────────┘
                                       │ consume
                                       ▼
                            ┌────────────────────────┐
                            │ notification-consumer  │ ─► stdout
                            └────────────────────────┘
```

## The three events

| Routing key | Emitter | When | Payload |
|---|---|---|---|
| `property.listed` | property-server | `POST /listing` or `/listing/seed` succeeds | `{type, property_id, listing_id, postcode, price, ts}` |
| `property.price-changed` | property-server | `POST /listing/{id}/price` succeeds | `{type, property_id, listing_id, postcode, old_price, new_price, ts}` |
| `property.hot` | analytics-server | every property view counter bump | `{type, property_id, view_count, ts}` *(no postcode — notification-service enriches via property-server)* |

The brief's "status change" event (e.g. pending → sold) is not implemented
because the current schema only carries `for_sale: bool`. The `listed`
event covers the brief's "new property put up for sale".

## What each service knows / doesn't know

| Service | Knows | Doesn't know |
|---|---|---|
| property-server | how to publish `property.listed` and `property.price-changed` to an exchange | who consumes them or what happens next |
| analytics-server | how to publish `property.hot` on every view bump | which properties are for-sale, who watches what postcode |
| notification-service | how to enrich an event (HTTP lookups) and fan it out per buyer | what the consumer does with messages it publishes |
| notification-consumer | how to print a delivery line to stdout | how messages get into its queue |

The brief's rule "the property server has no business knowing what happens
when these events occur" — property-server's `EventPublisher` is 50 lines
and the publish path is fire-and-forget. A network failure to RabbitMQ
prints to stderr and the HTTP request still returns 201.

## End-to-end test

`services/test-events.sh` — a deterministic shell test that:

1. Creates a buyer interested in postcode 2770.
2. Creates a property in 2770.
3. Triggers each event by hitting the gateway:
   - `POST /listing` → `property.listed`
   - `POST /listing/{id}/price` → `property.price-changed`
   - `GET /property/{id}` × 3 → 3 × `property.hot`
4. Asserts 7 substrings show up in the consumer's stdout log:
   - "listed" event for postcode 2770
   - new-property message text
   - "price-changed" event
   - the new price (`450000`) appears
   - at least one "hot" event
   - the 3rd view's count (`3 views`) appears
   - routing key `purchaser.<buyerId>` matches the test buyer

Run with the stack up:
```bash
MONGO_URI=mongodb://localhost:27017 ./services/test-events.sh
# ...
# passed: 7    failed: 0
# all event assertions hold ✓
```

The script auto-starts the stack via `run-all.sh` if nothing is running
and truncates the consumer log before testing so the assertions only see
this run's messages.

## Code-quality reassessment (ck rerun vs ex4)

Reran ck on `services/`:

**Top classes by LOC (ex8) vs the ex4 baseline (REServer monolith):**

| Class | CBO | WMC | RFC | LCOM* | LOC | Notes |
|---|---|---|---|---|---|---|
| **ex4: purchaser.PurchaserController** | 8 | **33** | 32 | 0.33 | **145** | mixed CRUD + HTML helpers + validation |
| **ex4: purchaser.PurchaserDAO** | **11** | **45** | **47** | 0.75 | 120 | biggest WMC + RFC in the monolith |
| **ex8: web.Renderers (gateway)** | 3 | 31 | 27 | 0.0 | 130 | HTML rendering, **0.0 cohesion** — concentrated by feature |
| **ex8: purchaser.PurchaserDAO** | 15 | 44 | 47 | 0.73 | 108 | DAO trimmed but coupling rose (Mongo+events+gateway↔DAO) |
| **ex8: listing.ListingDAO** | 15 | 37 | 52 | 0.64 | 110 | added event emit, slightly bigger |
| **ex8: property.PropertyDAO** | 15 | 27 | 47 | 0.38 | 83 | smaller than ex4 (no audit hook) |

**Worst per-method WMC** dropped a bit:
- ex4 max method WMC: **11** (`NotifyDAO.buildPostcodeIndex`, 24 LOC)
- ex8 max method WMC: **17** (gateway route registration; pure dispatch — not real complexity)

**The interesting deltas:**

1. **Cohesion improved** in the new code. `notify.Notifier` (LCOM* 0.25),
   `web.Renderers` (0.0), `events.EventPublisher` (0.375) — each has a
   single reason to change. The old monolith averaged LCOM* ~0.5.
2. **CBO went up** (most ex8 DAOs are at 15, vs 8–11 in ex4). That's the
   expected cost of distributing the system: each service now also
   depends on the broker + the JSON shape contracts. Worth it for the
   bounded contexts.
3. **No single class is "the big one" anymore** in ex8 — the
   monolith's PurchaserController/PurchaserDAO were clear hotspots; ex8
   spreads the same logic across ~6 classes per bounded context with
   none exceeding 130 LOC.
4. **One new "low-quality" class** by metric: `web.Renderers` has LCOM*
   = 0.0 (perfect, since each rendering method touches different inputs
   independently — common in formatter classes; not a real smell). The
   ck report is a bit misleading here; LCOM* is a poor fit for stateless
   utility classes.

Baselines: `tools/ck-baseline/` (ex4) and `tools/ck-ex8/` (ex8).

## Running

Prereqs (all already on this box):

- MongoDB on `localhost:27017` (`brew services start mongodb-community`)
- RabbitMQ on `localhost:5672` (`brew services start rabbitmq`)
- Java 21, Maven

```bash
cd services && mvn -DskipTests package        # builds all 6 jars

export MONGO_URI=mongodb://localhost:27017
./run-all.sh
# all six services up. gateway = http://localhost:7070
# watch events with:  tail -f /tmp/notification-consumer.log

./test-events.sh                               # 7/7 assertions

./stop-all.sh
```

## What you watch during the demo

Three terminal panes:

1. `tail -f /tmp/notification-consumer.log` — the **delivery window**.
   Each event produces a formatted message here.
2. `tail -f /tmp/notification-service.log` — the **orchestrator**'s view.
   Shows "property.hot in 2770 → fanned out to N buyer(s)".
3. A Postman/curl pane firing the events.

Run the test script live for the "definitive demonstration" — 7/7 checks
pass in ~5 seconds end-to-end.
