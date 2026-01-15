# Factory Machines Backend System

A high-performance backend system for processing and analyzing factory machine events.

## 📋 Overview

This system receives machine events from factory sensors, stores them efficiently, and provides real-time statistics and analytics. It handles thousands of concurrent events with built-in deduplication, validation, and update logic.

## 🏗️ Architecture

### Technology Stack
- **Language**: Java 17
- **Framework**: Spring Boot 3.2.1
- **Database**: MySQL 8.0 (H2 for tests)
- **Build Tool**: Maven
- **Testing**: JUnit 5, Spring Boot Test

### Layered Architecture

```
┌─────────────────────────────────────┐
│     REST Controller Layer           │
│  (EventController.java)             │
│  - Request validation               │
│  - Response formatting              │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│     Service Layer                   │
│  (EventService.java)                │
│  - Business logic                   │
│  - Validation                       │
│  - Deduplication                    │
│  - Transaction management           │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│     Repository Layer                │
│  (MachineEventRepository.java)      │
│  - Database queries                 │
│  - JPA operations                   │
└─────────────┬───────────────────────┘
              │
┌─────────────▼───────────────────────┐
│     Database Layer                  │
│  (MySQL Database)                   │
│  - Data persistence                 │
│  - Indexes for performance          │
└─────────────────────────────────────┘
```

## 🔄 Dedupe/Update Logic

### How It Works

The system uses a **payload hashing strategy** to distinguish between duplicates and updates:

1. **Payload Hash Calculation**
   - For each event, compute SHA-256 hash of: `eventTime | machineId | durationMs | defectCount | factoryId | lineId`
   - Excludes `eventId` (primary key) and `receivedTime` (server-controlled)

2. **Decision Logic**
   ```
   IF eventId exists in database:
       IF payload hash matches:
           → DEDUPE (ignore)
       ELSE:
           IF receivedTime is newer:
               → UPDATE (overwrite with new data)
           ELSE:
               → DEDUPE (ignore older version)
   ELSE:
       → ACCEPT (new event)
   ```

3. **Implementation Details**
   - Batch lookup: All events in a batch are checked against DB in one query
   - Atomic updates: Spring's `@Transactional` ensures consistency
   - Hash stored: `payloadHash` field prevents recalculation on future checks

### Example Scenarios

**Scenario 1: Identical Duplicate**
```json
Request 1: {"eventId": "E-1", "durationMs": 1000, ...}
Request 2: {"eventId": "E-1", "durationMs": 1000, ...}
Result: Request 2 is DEDUPED
```

**Scenario 2: Update with Newer Data**
```json
Request 1 (10:00 AM): {"eventId": "E-1", "durationMs": 1000, ...}
Request 2 (10:05 AM): {"eventId": "E-1", "durationMs": 2000, ...}
Result: Request 2 UPDATES the event
```

**Scenario 3: Late Arrival (Older Data)**
```json
Request 1 (10:05 AM): {"eventId": "E-1", "durationMs": 2000, ...}
Request 2 (10:10 AM, but contains old data): {"eventId": "E-1", "durationMs": 1000, ...}
Result: Request 2 is DEDUPED (receivedTime shows it's newer, but we reject it)
```

## 🔒 Thread-Safety

### Multi-Level Thread Safety

1. **Database Level**
   - Primary key constraint on `eventId` prevents duplicate insertions
   - `@Version` field for optimistic locking on updates
   - Database transaction isolation handles concurrent access

2. **Application Level**
   - `@Transactional` annotation ensures atomic operations
   - Each request runs in its own transaction
   - Spring manages transaction boundaries and rollback

3. **Concurrency Model**
   - Multiple threads can ingest batches simultaneously
   - Database handles locking and conflict resolution
   - No explicit Java locks needed (leverages DB ACID properties)

### Why This Approach Works

- **Stateless Service**: No shared mutable state in application layer
- **Database as Source of Truth**: All state managed by DB with proper isolation
- **Batch Processing**: Each batch is a single transaction, reducing lock contention
- **Optimistic Locking**: Version field detects concurrent modifications

### Testing Thread-Safety

Included test `testConcurrentIngestionThreadSafety()`:
- Spawns 10 threads
- Each processes 100 events
- Verifies no data corruption or lost updates

## 💾 Data Model

### Database Schema

```sql
CREATE TABLE machine_events (
    event_id VARCHAR(100) PRIMARY KEY,
    event_time TIMESTAMP(6) NOT NULL,
    received_time TIMESTAMP(6) NOT NULL,
    machine_id VARCHAR(50) NOT NULL,
    duration_ms BIGINT NOT NULL,
    defect_count INTEGER NOT NULL,
    payload_hash VARCHAR(255) NOT NULL,
    factory_id VARCHAR(50),
    line_id VARCHAR(50),
    version BIGINT -- for optimistic locking
);

-- Indexes for performance
CREATE INDEX idx_machine_time ON machine_events(machine_id, event_time);
CREATE INDEX idx_event_time ON machine_events(event_time);
CREATE INDEX idx_line_time ON machine_events(line_id, event_time);
```

### Field Descriptions

| Field | Type | Description |
|-------|------|-------------|
| `event_id` | String | Unique identifier (primary key) |
| `event_time` | Instant | When event occurred (used for queries) |
| `received_time` | Instant | When backend received event (for update logic) |
| `machine_id` | String | Machine identifier |
| `duration_ms` | Long | Event duration in milliseconds |
| `defect_count` | Integer | Number of defects (-1 = unknown) |
| `payload_hash` | String | SHA-256 hash for duplicate detection |
| `factory_id` | String | Factory identifier (optional) |
| `line_id` | String | Production line identifier (optional) |
| `version` | Long | Optimistic lock version |

## ⚡ Performance Strategy

### Goal
Process **1000 events in under 1 second** on a standard laptop.

### Optimizations Implemented

1. **Batch Processing**
   - Single transaction per batch (not per event)
   - Reduces transaction overhead by ~1000x
   - All DB operations in one commit

2. **Bulk Database Operations**
   - `findAllById()` fetches all existing events in one query
   - `saveAll()` persists all changes in one batch
   - Uses JDBC batch inserts when available

3. **Efficient Indexing**
   - Composite index on `(machine_id, event_time)` for stats queries
   - Index on `event_time` for time-range queries
   - Index on `(line_id, event_time)` for factory analytics

4. **Minimal Payload Hashing**
   - SHA-256 is fast (~1μs per hash)
   - Only essential fields included
   - Stored to avoid recalculation

5. **Database Optimization**
   - MySQL InnoDB engine for ACID compliance
   - Efficient indexing on query fields
   - Connection pooling via HikariCP

6. **Connection Pooling**
   - Spring Boot default HikariCP connection pool
   - Reuses connections across requests
   - Configurable for production workloads

### Performance Results

See [BENCHMARK.md](BENCHMARK.md) for detailed measurements.

Expected: **~200-400ms for 1000 events** on modern hardware.

## 🎯 Edge Cases & Assumptions

### Edge Cases Handled

1. **Duplicate Detection**
   - ✅ Identical payloads are deduped
   - ✅ Different payloads trigger updates
   - ✅ Out-of-order arrivals handled correctly

2. **Validation**
   - ✅ Negative duration rejected
   - ✅ Duration > 6 hours rejected
   - ✅ EventTime > 15 minutes in future rejected
   - ✅ Null/missing required fields rejected

3. **Special Values**
   - ✅ `defectCount = -1` stored but ignored in calculations
   - ✅ Null `factoryId`/`lineId` handled gracefully

4. **Boundary Conditions**
   - ✅ Empty batch returns all zeros
   - ✅ Start/end time boundaries are inclusive/exclusive
   - ✅ Zero-length time window returns valid results

5. **Concurrent Access**
   - ✅ Multiple threads can ingest simultaneously
   - ✅ Database ensures no lost updates
   - ✅ No race conditions in dedupe/update logic

### Assumptions Made

1. **Ordering**: `receivedTime` determines "winning" version in conflicts
2. **Time Zones**: All times in UTC (ISO-8601 format)
3. **Event IDs**: Globally unique across all machines
4. **Factory/Line**: Optional fields, can be null
5. **Defect Unknown**: `-1` is the only special value for unknown defects
6. **Window Calculation**: Uses `eventTime` for all queries (not `receivedTime`)

### Trade-offs

| Decision | Benefit | Cost |
|----------|---------|------|
| MySQL Database | Data persistence, production-ready | Requires MySQL installation |
| Payload hashing | Efficient comparison | Small CPU overhead |
| Server-controlled receivedTime | Prevents clock skew issues | Ignores client timestamp |
| Optimistic locking | High concurrency | Rare retry needed |
| Batch transactions | High throughput | Partial batch failure affects all |

## 🚀 Setup & Run Instructions

### Prerequisites
- Java 17 or higher
- Maven 3.6 or higher
- **MySQL 8.0 or higher** (running on localhost:3306)

[//]: # (  - See [MYSQL_SETUP.md]&#40;MYSQL_SETUP.md&#41; for detailed MySQL installation and configuration)

### Installation

1. **Setup MySQL** (if not already installed)
   - Install MySQL Server from https://dev.mysql.com/downloads/mysql/
   - Or use Docker: `docker run --name factory-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=factorydb -p 3306:3306 -d mysql:8.0`
   - Start MySQL service: `net start MySQL80`
   - The application will auto-create the `factorydb` database on first run

2. **Clone or extract the project**
   ```bash
   cd D:\Factory_Machines
   ```

3. **Configure database connection** (optional)
   - Default credentials: username=`root`, password=`root`
   - To change, edit `src/main/resources/application.properties`

[//]: # (   - See [MYSQL_SETUP.md]&#40;MYSQL_SETUP.md&#41; for configuration options)

4. **Build the project**
   ```powershell
   mvn clean install
   ```

5. **Run the application**
   ```powershell
   mvn spring-boot:run
   ```

6. **Application will start on** `http://localhost:8080`

### Running Tests

```powershell
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=EventServiceTest

# Run with detailed output
mvn test -X
```

### Accessing MySQL Database (Optional)

For debugging and viewing stored data:
```powershell
mysql -u root -p
# Enter your password when prompted
```

```sql
USE factorydb;
SHOW TABLES;
SELECT * FROM machine_events LIMIT 10;
```

## 📡 API Endpoints

### 1. Ingest Batch

**POST** `/events/batch`

Ingest multiple events in one request.

**Request Body:**
```json
[
  {
    "eventId": "E-1",
    "eventTime": "2026-01-15T10:12:03.123Z",
    "machineId": "M-001",
    "durationMs": 4312,
    "defectCount": 0,
    "factoryId": "F01",
    "lineId": "L1"
  },
  {
    "eventId": "E-2",
    "eventTime": "2026-01-15T10:15:00.000Z",
    "machineId": "M-002",
    "durationMs": 5000,
    "defectCount": -1
  }
]
```

**Response:**
```json
{
  "accepted": 950,
  "deduped": 30,
  "updated": 10,
  "rejected": 10,
  "rejections": [
    {
      "eventId": "E-99",
      "reason": "INVALID_DURATION: durationMs must be >= 0"
    }
  ]
}
```

**cURL Example:**
```bash
curl -X POST http://localhost:8080/events/batch \
  -H "Content-Type: application/json" \
  -d '[{"eventId":"E-1","eventTime":"2026-01-15T10:12:03.123Z","machineId":"M-001","durationMs":4312,"defectCount":0}]'
```

### 2. Query Statistics

**GET** `/stats?machineId=M-001&start=2026-01-15T00:00:00Z&end=2026-01-15T06:00:00Z`

Get statistics for a machine in a time window.

**Query Parameters:**
- `machineId` (required): Machine identifier
- `start` (required): Start time (inclusive), ISO-8601 format
- `end` (required): End time (exclusive), ISO-8601 format

**Response:**
```json
{
  "machineId": "M-001",
  "start": "2026-01-15T00:00:00.000Z",
  "end": "2026-01-15T06:00:00.000Z",
  "eventsCount": 1200,
  "defectsCount": 6,
  "avgDefectRate": 1.0,
  "status": "Healthy"
}
```

**cURL Example:**
```bash
curl "http://localhost:8080/stats?machineId=M-001&start=2026-01-15T00:00:00Z&end=2026-01-15T06:00:00Z"
```

### 3. Top Defect Lines

**GET** `/stats/top-defect-lines?factoryId=F01&from=2026-01-15T00:00:00Z&to=2026-01-15T23:59:59Z&limit=10`

Get production lines with most defects.

**Query Parameters:**
- `factoryId` (required): Factory identifier
- `from` (required): Start time (inclusive), ISO-8601 format
- `to` (required): End time (exclusive), ISO-8601 format
- `limit` (optional, default=10): Max results to return

**Response:**
```json
[
  {
    "lineId": "L1",
    "totalDefects": 150,
    "eventCount": 5000,
    "defectsPercent": 3.0
  },
  {
    "lineId": "L2",
    "totalDefects": 80,
    "eventCount": 4500,
    "defectsPercent": 1.78
  }
]
```

**cURL Example:**
```bash
curl "http://localhost:8080/stats/top-defect-lines?factoryId=F01&from=2026-01-15T00:00:00Z&to=2026-01-15T23:59:59Z&limit=10"
```

## 🧪 Test Coverage

Minimum 10 tests covering all requirements:

1. ✅ **testIdenticalDuplicateIsDeduped**: Same eventId + same payload → dedupe
2. ✅ **testDifferentPayloadNewerReceivedTimeUpdates**: Same eventId + different payload + newer time → update
3. ✅ **testDifferentPayloadOlderReceivedTimeIgnored**: Same eventId + different payload + older time → ignore
4. ✅ **testInvalidDurationRejected**: Duration < 0 or > 6 hours → reject
5. ✅ **testFutureEventTimeRejected**: EventTime > 15 min in future → reject
6. ✅ **testDefectCountMinusOneIgnoredInCalculations**: defectCount = -1 not counted in totals
7. ✅ **testStartEndBoundaryCorrectness**: Start inclusive, end exclusive
8. ✅ **testConcurrentIngestionThreadSafety**: 10 threads, 100 events each, no corruption
9. ✅ **testBatchProcessingPerformance**: 1000 events under 1 second
10. ✅ **testHealthStatusCalculation**: Status = "Healthy" if avgDefectRate < 2.0

Run tests:
```powershell
mvn test
```

## 🔮 Future Improvements

Given more time, I would enhance:

### 1. Database Enhancements
- Add database migrations (Flyway/Liquibase) for version control
- Implement read replicas for scaling
- Add database monitoring and performance tuning

### 2. Advanced Monitoring
- Add Prometheus metrics for event throughput
- Include Grafana dashboards for visualization
- Track p95/p99 latency metrics

### 3. Distributed Deployment
- Add Redis for distributed caching
- Implement message queue (Kafka/RabbitMQ) for async processing
- Support horizontal scaling with load balancer

### 4. Enhanced Security
- Add API authentication (JWT/OAuth2)
- Implement rate limiting per client
- Add request signing for tamper detection

### 5. Better Error Handling
- Partial batch success (accept valid, reject invalid)
- Retry mechanism for transient failures
- Dead letter queue for permanently failed events

### 6. Query Optimizations
- Add caching for frequent queries
- Implement read replicas for heavy read loads
- Support pagination for large result sets

### 7. Advanced Analytics
- Real-time alerting when defect rate spikes
- Predictive maintenance using ML models
- Trend analysis and anomaly detection

### 8. API Enhancements
- GraphQL endpoint for flexible queries
- Webhooks for event notifications
- Bulk export API for historical data

### 9. Documentation
- OpenAPI/Swagger for interactive API docs
- Architecture decision records (ADRs)
- Runbook for operations team

### 10. Testing
- Load testing with JMeter/Gatling
- Chaos engineering tests
- Integration tests with Testcontainers

## 📞 Support

For questions or issues, please refer to:
- Test cases for usage examples
- [BENCHMARK.md](BENCHMARK.md) for performance details
- Inline code comments for implementation details

---

**Built with ❤️ for factory automation and Industry 4.0**

