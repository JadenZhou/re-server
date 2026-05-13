# re-server

A lightweight real-estate data platform built for CS4530, with:

- **REServer**: a Java + Javalin API for querying property records
- **REDataLoader**: a Java CSV ingestion tool for loading NSW property sales into MongoDB

---

## ✨ What this project does

- Loads NSW real-estate sales data from CSV into MongoDB
- Exposes HTTP endpoints to fetch properties by:
  - property ID
  - postcode
  - optional price range filters
- Supports both:
  - a shared MongoDB Atlas cluster
  - an optional local MongoDB container for development

---

## 🧱 Repository structure

```text
re-server/
├── REServer/        # REST API service (Javalin)
├── REDataLoader/    # CSV -> MongoDB loader
├── docker-compose.yml
└── .env.example
```

---

## 🏗️ Architecture

```text
CSV file (nsw_property_data.csv)
        │
        ▼
REDataLoader (one-off batch loader)
        │
        ▼
MongoDB (Atlas or local container)
        │
        ▼
REServer (HTTP API on :7070)
```

---

## ✅ Prerequisites

Choose one workflow:

### Option A: Docker (recommended)
- Docker + Docker Compose

### Option B: Local Java/Maven
- Java 21 (recommended, matches Docker images)
- Maven 3.9+
- MongoDB Atlas URI or local MongoDB

---

## ⚙️ Environment setup

From repository root:

```bash
cp .env.example .env
```

Set `MONGO_URI` in `.env`.

- Atlas example: `mongodb+srv://<user>:<password>@<cluster>/`
- Local Docker Mongo example: `mongodb://mongo:27017`

> `.env` is gitignored — do not commit credentials.

---

## 🚀 Quick start (Docker)

### 1) Start API server

```bash
docker compose up --build server
```

Server runs on **http://localhost:7070**.

### 2) (Optional) Start local MongoDB

```bash
docker compose --profile local up -d mongo
```

Use this with `MONGO_URI=mongodb://mongo:27017` in `.env`.

### 3) Load dataset into MongoDB

Place your CSV at `./data/nsw_property_data.csv`, then run:

```bash
docker compose run --rm loader
```

The loader resets and repopulates `realestate.properties`, then builds indexes.

---

## 💻 Run without Docker

### API server

```bash
cd REServer
mvn clean package
MONGO_URI="<your-mongo-uri>" java -jar target/*-jar-with-dependencies.jar
```

### Data loader

```bash
cd REDataLoader
mvn clean package
MONGO_URI="<your-mongo-uri>" RE_CSV_PATH="../data/nsw_property_data.csv" java -jar target/*-jar-with-dependencies.jar
```

---

## 📚 API reference

Base URL: `http://localhost:7070`

| Method | Path | Description |
|---|---|---|
| GET | `/` | Health message |
| GET | `/property` | Get properties (supports `minPrice` / `maxPrice`) |
| GET | `/property/{propertyID}` | Get latest sale row for property ID |
| GET | `/property/postcode/{postcode}` | Get properties in a postcode |
| POST | `/property` | Insert a property record |

### Examples

```bash
# Health
curl http://localhost:7070/

# All properties (capped)
curl http://localhost:7070/property

# Price range filter
curl "http://localhost:7070/property?minPrice=1000000&maxPrice=3000000"

# By property ID
curl http://localhost:7070/property/123456

# By postcode
curl http://localhost:7070/property/postcode/2000
```

`POST /property` expects JSON like:

```json
{
  "propertyID": "123456",
  "postcode": "2000",
  "propertyPrice": "1850000",
  "forSale": false
}
```

---

## 🧪 Development checks

From each module:

```bash
cd REServer && mvn test
cd REDataLoader && mvn test
```

(Currently, there are no test classes; Maven validates compilation and project wiring.)

---

## 📝 Notes

- Data lives in database `realestate`, collection `properties`.
- API responses for list/detail routes are currently HTML table views.
- Queries are capped to prevent unbounded response sizes.

---

## 🤝 Team workflow tips

- Keep credentials only in `.env`
- Run loader separately from server startup (faster iteration)
- Use local Mongo profile for offline or throttling-free development

