# Exercise 7 — Microservices Architecture

The monolithic REServer is replaced by four independently-runnable jars. The
brief asked for an API gateway plus three context-bounded services. Each
service builds its own fat jar and runs on its own port; they communicate
only via HTTP (no shared in-process state).

This branch builds on the `alternate/db` SQLite work. SQLite is naturally
multi-process-friendly: all three data-owning services point at the same
file (`SQLITE_PATH`), but each service's `db/Db.java` only knows about its
own tables — the bounded-context rule enforced at the schema layer.

## The four services

| Service | Port | Tables it owns | Public to gateway |
|---|---|---|---|
| **gateway** | 7070 | none — pure orchestration | yes (only one clients hit) |
| **property-server** | 7071 | `postcodes`, `properties`, `listings`, `listing_prices` | gateway-only |
| **purchaser-server** | 7072 | `accounts`, `postcode_interest`, `purchases` | gateway-only |
| **analytics-server** | 7073 | `property_views`, `postcode_searches` | gateway-only |

The teammate's monolithic schema had `search_count` columns on `properties`
and `postcodes`. **Strict bounded contexts** required moving those into the
analytics service: `property_views(property_id PK, count)` and
`postcode_searches(post_code PK, count)`. Property-server and purchaser-
server literally cannot see view/search counts now — they would have to ask
the analytics service over HTTP, and in practice the gateway is the only
caller that does that.

## Where orchestration lives

The brief flagged this as the key design decision. Two options were on the
table:

| Approach | Pros | Cons |
|---|---|---|
| **Gateway orchestrates** (chosen) | Single integration point; downstream services stay single-purpose; no service-to-service coupling | Gateway grows logic over time; single hop overhead per fan-out |
| Service-to-service chained calls | Less work in the gateway | Couples services to each other; harder to evolve; brief explicitly says "gateway is the client of the other three" |

We picked gateway-orchestrates throughout, with one helper class
(`notify.Notifier`) for the multi-step notifier flow.

## Fan-out per endpoint

| Client endpoint (port 7070) | Property | Purchaser | Analytics |
|---|---|---|---|
| `GET /property` | GET `/property` (+ optional `?minPrice`, `?maxPrice`) | — | — |
| `POST /property` | POST `/property` | — | — |
| `GET /property/{id}` | GET `/property/{id}` | — | POST `/views/property/{id}` (bump) |
| `GET /property/postcode/{pc}` | GET `/property/postcode/{pc}` | — | POST `/searches/postcode/{pc}` (bump, only on non-empty) |
| `POST /listing` | POST `/listing` | — | — |
| `POST /listing/seed` | POST `/listing/seed` | — | — |
| `GET /listing/{id}` | GET `/listing/{id}?withHistory=true` | — | — |
| `POST /listing/{id}/price` | POST `/listing/{id}/price` | — | — |
| `POST /purchaser` | — | POST `/purchaser` | — |
| `GET /purchaser/{id}` | — | GET `/purchaser/{id}` | — |
| `GET /purchaser/postcode/{pc}` | — | GET `/purchaser/postcode/{pc}` | POST `/searches/postcode/{pc}` (bump) |
| `POST /purchaser/{id}/interest` | — | POST `/purchaser/{id}/interest` | — |
| `DELETE /purchaser/{id}/interest/{pc}` | — | DELETE `/purchaser/{id}/interest/{pc}` | — |
| `POST /purchaser/seed` | — | POST `/purchaser/seed?count=N` | — |
| `GET /stats/property/{id}` | — | — | GET `/views/property/{id}` |
| `GET /stats/postcode/{pc}` | — | — | GET `/searches/postcode/{pc}` |
| `GET /notify` (orchestrated) | GET `/internal/for-sale` | GET `/purchaser` | — |

The two POSTs to analytics on `/property/{id}` and `/property/postcode/{pc}`
are fire-and-forget — the gateway returns the property data even if the
analytics bump fails. This decouples client-visible behavior from the
analytics service's availability.

## HTTP client

`java.net.http.HttpClient` (built into Java 21). One `client.ServiceClient`
instance per gateway wraps it, knows the three downstream base URLs, and
handles JSON (de)serialization via Jackson (already bundled with Javalin).

The whole client is ~120 lines and has no Spring / Feign / etc. — pure JDK.

## Running it

### Build all jars

```bash
cd services
mvn -DskipTests package          # builds 4 jars (one per module)
```

### Start the stack (4 terminals, or background jobs)

```bash
# point all services at the same SQLite file
export SQLITE_PATH=./data/re-server.db

ANALYTICS_PORT=7073 java -jar services/analytics-server/target/analytics-server-jar-with-dependencies.jar &
PROPERTY_PORT=7071  java -jar services/property-server/target/property-server-jar-with-dependencies.jar  &
PURCHASER_PORT=7072 java -jar services/purchaser-server/target/purchaser-server-jar-with-dependencies.jar &
GATEWAY_PORT=7070   java -jar services/gateway/target/gateway-jar-with-dependencies.jar &
```

### Hit the gateway

The existing Postman collection (`re-server.postman_collection.json`) works
unchanged because the gateway exposes the same paths on the same port the
monolith did.

## Demo path the instructor sees

```bash
# 1. all four services up
curl -s http://localhost:7070/   # "Real Estate gateway is running"
curl -s http://localhost:7071/   # "property-server up"
curl -s http://localhost:7072/   # "purchaser-server up"
curl -s http://localhost:7073/   # "analytics-server up"

# 2. create a buyer (one hop: gateway -> purchaser-server)
curl -X POST http://localhost:7070/purchaser \
  -H 'Content-Type: application/json' \
  -d '{"name":"Demo","email":"d@x.com","postcodes":["2770"]}'
# → {"purchaserId":"6a0c..."}

# 3. create a property + listing (one hop each: gateway -> property-server)
curl -X POST http://localhost:7070/property \
  -H 'Content-Type: application/json' \
  -d '{"postcode":"2770","propertyPrice":"500000","address":"1 Test St"}'
# → {"propertyID":"6a0c...", ...}

curl -X POST http://localhost:7070/listing \
  -H 'Content-Type: application/json' \
  -d '{"propertyId":"<id>","price":520000}'

# 4. read property 3x (gateway -> property-server + analytics-server bump)
for i in 1 2 3; do curl -s http://localhost:7070/property/<id> > /dev/null; done
curl -s http://localhost:7070/stats/property/<id>
# → {"count":3, ...}      # analytics-server recorded all 3

# 5. /notify orchestration (gateway calls property-server + purchaser-server)
curl -s http://localhost:7070/notify | python3 -m json.tool
# → one notification per buyer with matching for-sale properties
```

## Bounded-context proof

Each service can demonstrably *only* read its own tables, because the
service's `db/Db.java` only references those tables and the schemas
exclude foreign columns:

```bash
# property-server's schema:
grep CREATE services/property-server/src/main/java/db/Db.java
# → postcodes, properties, listings, listing_prices   (no search_count, no accounts)

# purchaser-server's schema:
grep CREATE services/purchaser-server/src/main/java/db/Db.java
# → accounts, postcode_interest, purchases   (no properties)

# analytics-server's schema:
grep CREATE services/analytics-server/src/main/java/db/Db.java
# → property_views, postcode_searches   (and nothing else)
```

A code review can verify with one grep that no service queries another's
tables.
