# Exercise 5 — Access Counters & Holistic Data Model

## Step 1: Access Counters

### Data model changes

Two counters were added:

1. **`view_count` field on each `properties` document.**
   - Same `property_id` appears in multiple sale rows (one per recorded sale),
     so `incrementViewCount` uses `updateMany` to bump every row sharing that
     `property_id`. This keeps the counter coherent regardless of which row is
     returned by the lookup. `view_count` defaults to absent → treated as 0 on
     read.

2. **New `postcode_stats` collection.**
   - Schema: `{ postcode: String, search_count: Long }`.
   - Upserted via `findOneAndUpdate` with `$inc`, so the first search creates
     the row and subsequent searches atomically bump it.
   - Lives in its own collection (rather than on a postcode-keyed field
     somewhere else) because there is no existing "postcode" entity — the
     value is just an attribute on properties and purchasers.

### API changes

| Endpoint | Counter bumped |
|---|---|
| `GET /property/{id}` (when found) | `view_count` on every row with that `property_id` |
| `GET /property/postcode/{pc}` (when non-empty) | `postcode_stats.search_count` |
| `GET /purchaser/postcode/{pc}` (when non-empty) | `postcode_stats.search_count` |
| `GET /stats/postcode/{pc}` *(new)* | read-only: returns `{postcode, searchCount}` |

404s do not bump counters (no signal of "interest" for a missing record).
Range queries (`?minPrice=...`) are intentionally excluded — they return many
properties and don't represent attention on a specific one.

Property view counts are surfaced in the `Views` column of the existing
property HTML table. Postcode search counts are read via the new
`/stats/postcode/{pc}` JSON endpoint (and consumed by the notifier — see
below).

### Code structure

- `stats.PostcodeStats` — interface (`incrementSearch`, `getSearchCount`).
- `stats.MongoPostcodeStats` — production impl, persists in `postcode_stats`.
- `stats.InMemoryPostcodeStats` — `ConcurrentHashMap<String, AtomicLong>`
  used by unit tests so Mongo isn't required to exercise the contract.

Controllers receive `PostcodeStats` via constructor injection. The
`PropertyDAO.incrementViewCount` method lives next to the other property
write operations (consistent with the existing layout).

### Tests

`stats.PostcodeStatsTest` — 5 tests against `InMemoryPostcodeStats`:

| Test | What it proves |
|---|---|
| `unsearchedPostcodeReadsZero` | reading an absent key returns 0, not null/exception |
| `incrementReturnsNewTotalAndPersists` | each call returns the post-increment total, and the total persists |
| `countersForDifferentPostcodesAreIndependent` | bumps don't leak across postcodes |
| `nullAndBlankPostcodesAreIgnored` | defensive input handling matches Mongo impl |
| `concurrentIncrementsCountAllOperations` | 8 threads × 1000 increments → exactly 8000 (atomicity) |

The two impls share the same interface so the test exercises the same
behavioural contract `MongoPostcodeStats` upholds (Mongo's `$inc` provides
the analogous atomicity in production).

### Notifier enhancement using these counters

The existing notifier lists every for-sale property in a buyer's watched
postcodes. With access counts available, that report can be enriched in
three useful ways:

1. **Sort matches by view count.** Each `PropertyForSale` already carries
   `property_id`; `NotifyService` can join against `view_count` and emit the
   matches in descending popularity, so buyers see the hottest listings
   first.

2. **"Trending in your postcodes" digest.** Periodically scan
   `postcode_stats`, find postcodes whose `search_count` has crossed a
   threshold (or grown sharply versus a baseline), and notify every buyer
   watching that postcode — even if no individual property is hot yet — so
   they know demand in the area is rising.

3. **High-demand alerts.** When `view_count` on a property crosses a
   threshold (e.g. ≥ N views in a window), the notifier sends an immediate
   "this property is getting attention — act fast" alert to every buyer
   watching that property's postcode. Implementable as a scheduled scan or
   as a side-effect inside `incrementViewCount` (push when the post-bump
   value crosses the threshold).

The shape of `NotifyService` doesn't change: it stays a pure function over
inputs. The DAO gathers the additional counters and passes them in, keeping
the service trivially unit-testable.

---

## Step 2: Holistic Data Model

*(See `EX5_DATA_MODEL.pdf` — UML class diagram of the full sale lifecycle:
agents, owners, sellers/purchasers, listings with state machine, open
houses, property details.)*
