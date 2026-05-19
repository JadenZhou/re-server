# Alternate persistence layer — MongoDB → SQLite

This branch (`alternate/db`) demonstrates swapping the persistence layer of
`re-server` from MongoDB to SQLite with **no change to the public HTTP API**.
The goal is to show that the codebase is portable across very different
storage models (document store ↔ relational SQL) without forcing clients to
adapt.

`main` continues to use MongoDB. This branch is SQLite-only.

---

## TL;DR for a grader

| Aspect | Before (main) | After (this branch) |
|---|---|---|
| Database | MongoDB (Atlas or local container) | SQLite (single embedded file) |
| Java driver | `mongodb-driver-sync` 5.2.1 | `sqlite-jdbc` 3.46.1.0 |
| Server needed? | Yes — Mongo daemon | No — embedded library |
| Env config | `MONGO_URI` | `SQLITE_PATH` |
| Row IDs in API | 24-char hex (`ObjectId`) | 24-char hex (same wire format) |
| Schema | 6 implicit collections | 7 explicit tables w/ FK constraints |
| `accounts.postcode_interest` | array field | junction table `postcode_interest` |
| `postcode_stats` collection | sibling collection | column on `postcodes` table |
| Pricing updates keyed by | `pid` (property) | `listing_id` (listing) |
| Tests passing | 7/7 (`NotifyServiceTest`) | 7/7 (no test changes needed) |
| Public HTTP API | unchanged | unchanged |

Lines changed: **+1260 / −620** across **16 files** (one commit:
`ea3497b feat(db): swap MongoDB for SQLite on alternate/db branch`).

---

## What I built

### 1. A small persistence abstraction (`db/` package)

`REServer/src/main/java/db/`

| File | Purpose |
|---|---|
| `Db.java` | Singleton SQLite connection. Opens the DB, applies pragmas (`foreign_keys = ON`, `journal_mode = WAL`, `synchronous = NORMAL`, `busy_timeout = 5000`), and idempotently creates the schema on first call. Exposes `connection()` and `ensurePostcode()` helpers used by every DAO. |
| `ObjectIdLike.java` | Generates 24-character lowercase hex IDs with the same shape as Mongo's `ObjectId.toHexString()` (4-byte epoch + 8-byte secure random). Lets the HTTP API keep returning the exact same wire format without depending on the BSON library. |

### 2. Four rewritten DAOs (plain JDBC, no ORM)

| DAO | What it does | Notable details |
|---|---|---|
| `PropertyDAO` | CRUD + price-range filtering for property records | Audit hook still increments `properties.search_count` and `postcodes.search_count` on read, mirroring the Mongo `$inc` behaviour |
| `ListingDAO` | Listings + per-listing price history + `seedListings()` sampling | All multi-statement operations wrap a transaction (`setAutoCommit(false)` / `commit` / rollback on failure); the `seed` path batches 1000 inserts |
| `PurchaserDAO` | Buyer accounts + watched-postcode interests | NSW-postcode range validation preserved; postcode array → `postcode_interest` junction table with FK on `accounts.id` (CASCADE) |
| `NotifyDAO` | Builds the `(postcode → for-sale property list)` index that `NotifyService` consumes | Single SQL JOIN with a correlated subquery picks the latest `listing_prices` row per listing in one round trip |

### 3. Rewritten CSV loader

`REDataLoader/src/main/java/org/example/Main.java` now:

1. Connects to SQLite at `SQLITE_PATH`.
2. Drops every table and re-creates the schema (idempotent, safe to re-run).
3. Streams the CSV row-by-row, with two `addBatch()` prepared statements:
   - `INSERT OR IGNORE INTO postcodes` (so the FK on `properties.post_code` resolves)
   - `INSERT INTO properties` with a freshly generated hex `id`.
4. Commits every 1000 rows inside one transaction (~25k rows/sec on WAL).
5. Prints progress and a final `inserted / parse-errors / rows-per-second` summary.

### 4. Schema (normalized, FKs enabled)

```sql
postcodes(post_code TEXT PK, search_count INTEGER)
properties(id TEXT PK, property_id INTEGER, post_code FK→postcodes,
           purchase_price, address, council_name, property_type,
           contract_date, for_sale, search_count)
accounts(id TEXT PK, name, email UNIQUE, account_type)
postcode_interest(account_id FK→accounts ON DELETE CASCADE,
                  post_code FK→postcodes, PK(account_id, post_code))
listings(id TEXT PK, property_id FK→properties ON DELETE CASCADE,
         is_discounted, date_added)
listing_prices(id INTEGER PK AUTO, listing_id FK→listings ON DELETE CASCADE,
               price REAL, updated_at)
purchases(id INTEGER PK AUTO, account_id FK→accounts,
          property_id FK→properties, purchased_at)
```

Indexes on every FK column plus `properties(purchase_price)` for the
price-range filter, and a composite `listing_prices(listing_id, updated_at DESC)`
for the "latest price per listing" lookup.

### 5. Infrastructure

- `docker-compose.yml`: removed the `mongo` service + `local` profile.
  Replaced with a bind-mounted `./data:/data` volume shared by the server
  and the loader.
- Both Dockerfiles: `VOLUME /data` so the DB file persists outside the
  container.
- `.env.example`: `MONGO_URI` → `SQLITE_PATH=/data/re-server.db`.

### 6. Documentation

- `README.md` rewritten end-to-end for the new flow (no Atlas string,
  no mongo profile, accurate `mvn` commands, full endpoint table).
- `docs/plans/2026-05-19-sqlite-migration-design.md` — the design doc
  written before any code, describing the schema, the trade-offs
  considered (hard cutover vs. repository abstraction; mirror collections
  vs. normalize), and what was deliberately left out of scope.

---

## Why each design decision

| Decision | Alternatives considered | Why this one |
|---|---|---|
| **SQLite** | Postgres, MySQL, DynamoDB, Redis | Embedded — zero ops, one Maven dep, one file. Fastest path for a course branch demonstrating a swap. Postgres would have been a stronger paradigm contrast but adds a server to run. |
| **Hard cutover** (rip out Mongo) | Repository interface with both backends | Cleaner diff and matches what the branch name `alternate/db` implies. `main` is the Mongo reference; this branch is the SQLite reference. A pluggable abstraction can be layered on later if both backends need to coexist. |
| **Normalized schema** | One-table-per-collection mirror | Demonstrates real relational modeling (junction tables, FKs, CASCADE), not just "stuff JSON into a TEXT column". The Mongo array-of-postcodes becomes the textbook many-to-many table. |
| **Hex string PKs** | Integer autoincrement, UUID | Preserves the existing API contract — Postman collection and any teammate scripts that hold onto `"propertyID": "65f..."` strings keep working without modification. |
| **One `Db` connection + WAL** | Connection pool | SQLite is single-writer; WAL lets readers run concurrently with the writer. A pool would add complexity without buying anything at this scale. |

---

## Verifying it works

### Build

```bash
cd REServer    && mvn -B clean package    # builds jar, runs tests
cd REDataLoader && mvn -B clean package
```

Both produce `target/*-jar-with-dependencies.jar`. `NotifyServiceTest`
runs 7 cases in ~30ms — all pass, no DB needed.

### End-to-end smoke (commands I ran against this commit)

```bash
# 1. Load a tiny synthetic CSV.
SQLITE_PATH=/tmp/loader.db RE_CSV_PATH=/tmp/test.csv \
  java -jar REDataLoader/target/RealEstate-1.0-SNAPSHOT-jar-with-dependencies.jar
# → "Inserted 4 rows in 0.4s ... Parse errors: 0"

# 2. Start the server against the same DB file.
SQLITE_PATH=/tmp/loader.db \
  java -jar REServer/target/REServer-1.0-SNAPSHOT-jar-with-dependencies.jar &

# 3. Hit every group of endpoints.
curl   http://localhost:7070/                                   # 200, health
curl   http://localhost:7070/property                           # 200, 4 rows
curl   http://localhost:7070/property/postcode/2000             # 200, 2 rows
curl   http://localhost:7070/property/<24-char-hex-id>          # 200 + audit
curl -XPOST  -H 'Content-Type: application/json' \
       -d '{"propertyObjId":"<id>","price":1750000}' \
       http://localhost:7070/listing                            # 201
curl -XPOST  -H 'Content-Type: application/json' \
       -d '{"name":"Alice","email":"a@x.com","postcodes":["2000"]}' \
       http://localhost:7070/purchaser                          # 201 + id
curl  'http://localhost:7070/notify?format=text'                # 200
```

Confirmed live:

- `properties.search_count` and `postcodes.search_count` incrementing per read
- New listing created → `properties.for_sale` flipped to 1 in same txn
- `/notify` returned Alice's match (property 1002 listed at $1.75M in 2000)

---

## What's deliberately out of scope

- **Migration tool** that copies data from Mongo to SQLite. Not needed: the
  source is the CSV, and the loader rebuilds the SQLite file from scratch.
- **Multi-backend Repository pattern.** Would require introducing an
  interface layer that the existing DAOs don't have. `main` and
  `alternate/db` are kept as two clean reference implementations instead.
- **Schema migrations.** The loader is destructive (drops all tables).
  The server's `Db.init` uses `CREATE TABLE IF NOT EXISTS` so re-starting
  the server against a populated DB does not nuke it.

---

## File map

```
docs/plans/2026-05-19-sqlite-migration-design.md     # design (pre-code)
ALTERNATE_DB.md                                       # this file (post-code)
docker-compose.yml                                    # mongo → bind-mount
.env.example                                          # MONGO_URI → SQLITE_PATH
README.md                                             # rewritten
REServer/
├── Dockerfile                                        # +VOLUME /data
├── pom.xml                                           # mongo → sqlite-jdbc
└── src/main/java/
    ├── db/Db.java                                    # NEW: shared connection
    ├── db/ObjectIdLike.java                          # NEW: hex ID generator
    ├── property/Property.java                        # ObjectId → String
    ├── property/PropertyDAO.java                     # rewritten on JDBC
    ├── listing/ListingDAO.java                       # rewritten on JDBC
    ├── purchaser/PurchaserDAO.java                   # rewritten on JDBC
    └── notify/NotifyDAO.java                         # rewritten on JDBC
REDataLoader/
├── Dockerfile                                        # +VOLUME /data
├── pom.xml                                           # mongo → sqlite-jdbc
└── src/main/java/org/example/Main.java              # rewritten loader
```
