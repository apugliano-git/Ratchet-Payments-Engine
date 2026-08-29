# Ratchet-Payments-Engine
Resilient reservation and payment engine built to guarantee transactional integrity under high concurrency — no double-booking, no lost or duplicated payments, even under simultaneous demand or partial service failures. Built with Spring Boot, PostgreSQL, Redis and the Outbox pattern.

## Monorepo Structure

This project uses a Maven multi-module structure, containing the following independent Spring Boot microservices:

*   `inventory-service/` - Port: `8081`
*   `reservation-service/` - Port: `8082`
*   `payment-service/` - Port: `8083`
*   `notification-service/` - Port: `8084`
*   `infra/` - Contains the `docker-compose.yml` for infrastructure components (PostgreSQL, Redis).
*   `docs/` - Directory for Architecture Decision Records (ADRs) and documentation.

## Running the Application Locally

### 1. Infrastructure (Database & Cache)
To start the backing services (PostgreSQL 16 and Redis 7), navigate to the root of the repository and use Docker Compose:

```bash
cd infra
docker-compose up -d
```
*   **PostgreSQL**: `localhost:5432` (User: `ratchet`, DB: `ratchet_db`, Password: `ratchet_password`)
*   **Redis**: `localhost:6379`

### 2. Building the Monorepo
From the root of the repository, run a clean install to build all services:

```bash
mvn clean install
```

### 3. Starting the Services
You can run each service independently using the Spring Boot Maven Plugin. Open separate terminal tabs for each service:

**Inventory Service (8081):**
```bash
cd inventory-service
mvn spring-boot:run
```

**Reservation Service (8082):**
```bash
cd reservation-service
mvn spring-boot:run
```

**Payment Service (8083):**
```bash
cd payment-service
mvn spring-boot:run
```

**Notification Service (8084):**
```bash
cd notification-service
mvn spring-boot:run
```

### Health Check Endpoints
Each service provides a simple health check endpoint to confirm it's running:
*   Inventory: `curl http://localhost:8081/health`
*   Reservation: `curl http://localhost:8082/health`
*   Payment: `curl http://localhost:8083/health`
*   Notification: `curl http://localhost:8084/health`
