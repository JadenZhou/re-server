# REServer — Polish Pass

Static analysis + refactor + OpenAPI/Swagger.

## Analyzers

| Tool | Purpose |
|---|---|
| **PMD 7.17** (maven-pmd-plugin 3.28) | Rule-based linter — best practices, code style, design, error-prone, multithreading, performance |
| **ck 0.7.1** (Chidamber & Kemerer) | OO metrics per class/method — WMC (complexity), CBO (coupling), RFC (response set), LCOM (cohesion) |

Ruleset: `/tmp/re-analysis/pmd-ruleset.xml` (built-in PMD categories with library-noise suppressed — `org.bson.Document` is not a Java collection, `java.util.Date` is what the Mongo BSON layer expects).

## Baseline → After

### PMD totals
| | Before | After | Δ |
|---|--:|--:|--:|
| Total violations | **179** | **83** | **−54%** |

### Top rule deltas
| Rule | Before | After |
|---|--:|--:|
| ControlStatementBraces (missing `{}` on if/else) | 42 | 3 |
| ConsecutiveLiteralAppends / AppendsShouldReuse (StringBuilder abuse) | 63 | 30 |
| LooseCoupling — already excluded for BSON Document | — | — |
| AvoidDuplicateLiterals | 15 | 2 |
| LambdaCanBeMethodReference | 7 | 0 |
| InsufficientStringBufferDeclaration (no initial capacity) | 7 | 0 |
| CloseResource (leaked MongoClient) | 4 | 1 |
| CyclomaticComplexity hot spot | 1 | 0 |
| UnusedPrivateField / UseUtilityClass | 2 | 0 |

### ck — worst-WMC method
`notify.NotifyDAO::buildPostcodeIndex` WMC **11 → 2** (split into `forSalePropertyIds`, `latestPricePerProperty`, `assemblePostcodeIndex`).

### Per-file violations
| File | Before | After |
|---|--:|--:|
| PurchaserController | 30 | 10 |
| ListingController | 26 | 8 |
| PurchaserDAO | 25 | 4 |
| ListingDAO | 23 | 8 |
| NotifyController | 19 | 6 |
| PropertyController | 19 | 15 |
| REServer | 13 | 4 |
| NotifyDAO | 10 | 3 |
| PropertyDAO | 6 | 0 |

## What changed

1. **Shared MongoClient** — `app.Mongo` lazy thread-safe singleton with shutdown hook. Was: every DAO opened its own client + leaked (`CloseResource` × 4). Now: one connection pool for the JVM.
2. **Pre-sized StringBuilders** — every HTML renderer declares an initial capacity (`new StringBuilder(1024)` etc) so the buffer doesn't grow + recopy on every append.
3. **Brace discipline** — every single-statement `if/else/while` now uses `{}`. Defends against the next-line "looks-indented-but-isn't" bug.
4. **Method references** — Javalin route handlers `ctx -> handler.foo(ctx)` → `handler::foo` where the param shape matches.
5. **Magic numbers / dup literals → constants** — `BATCH_SIZE`, `SEED_SAMPLE`, `LISTING_MARKUP`, `MAX_SEED_COUNT`, `FIELD_*` collection field names, `MSG_PURCHASER_NOT_FOUND`. PMD's AvoidDuplicateLiterals dropped 15 → 2.
6. **Decomposed NotifyDAO** — `buildPostcodeIndex` (WMC 11) split into three single-purpose helpers. Easier to test, read, and modify.
7. **Shared HTML helper** — `app.Html.escape` / `errorPage`. Was: each controller had its own `escape` + ad-hoc error HTML. Now: one source of truth.
8. **Utility-class hygiene** — `REServer` and `Mongo` and `Html` are now `final` with private constructors (UseUtilityClass).

## OpenAPI / Swagger

Bonus task 3.

Dependencies added to `pom.xml`:
- `io.javalin.community.openapi:javalin-openapi-plugin`
- `io.javalin.community.openapi:javalin-swagger-plugin`
- `io.javalin.community.openapi:javalin-redoc-plugin`
- `openapi-annotation-processor` wired into `maven-compiler-plugin`

Plugins registered in `REServer.main`. Every controller endpoint annotated with `@OpenApi(...)` (path, method, summary, tags, params, request/response).

Live URLs (server on :7070):
- `GET /openapi` — raw OpenAPI 3.0.3 JSON (14 paths)
- `GET /swagger` — Swagger UI
- `GET /redoc` — ReDoc UI

## Validation

```
mvn test           → 7/7 pass (NotifyServiceTest)
mvn package        → BUILD SUCCESS
java -jar ...      → server up, /, /property, /listing, /purchaser, /notify all 200
```

## How to reproduce

```bash
# PMD
cd REServer && mvn pmd:pmd
open target/reports/pmd.html

# ck (clone + build first)
git clone https://github.com/mauricioaniche/ck.git /tmp/ck && (cd /tmp/ck && mvn -DskipTests package)
java -jar /tmp/ck/target/ck-*-jar-with-dependencies.jar src/main/java false 0 false /tmp/ck-out/
```
