# Ratchet Payments Engine

Motor de reservas y pagos resiliente a alta concurrencia. Garantiza integridad transaccional absoluta mediante arquitecturas dirigidas por eventos, asegurando cero sobreventas (double-booking) y eliminando el riesgo de pérdida o duplicación de pagos.

## Arquitectura

El ecosistema está compuesto por 4 microservicios independientes:

1. **inventory-service**: (Gateway futuro) Preparado para escalamiento de catálogos complejos y metadatos.
2. **reservation-service**: Núcleo transaccional. Gestiona el inventario real y el ciclo de vida temporal de las reservas (holds) con control estricto de concurrencia.
3. **payment-service**: Receptor asíncrono. Procesa webhooks del Gateway de pago, verifica firmas y reconcilia pagos contra reservas de forma segura.
4. **notification-service**: Consumidor final idempotente que reacciona a los pagos confirmados.

**Flujo End-to-End:**
`Cypher Auth (Login)` ➔ `POST /holds (Reservation)` ➔ `Mercado Pago Sandbox` ➔ `Webhook (Payment)` ➔ `POST /internal/holds/confirm` ➔ `Outbox DB` ➔ `Redpanda: reservation.events.v1` ➔ `Notification Service`

> **Nota sobre Endpoints Internos:** Los endpoints bajo el prefijo `/internal/**` (como `/internal/holds/{id}/confirm` para la consolidación asíncrona de pagos y `/internal/resources` como herramienta de inyección para testing) son de estricto uso interno entre microservicios o para automatización local. No forman parte de la API pública consumida por los clientes finales o el frontend.

## Stack Tecnológico
- **Core:** Java 21, Spring Boot 3.3.3
- **Datos & Caché:** PostgreSQL 16, Redis 7
- **Event Streaming:** Redpanda (Kafka API compatible)
- **Identidad:** Cypher Auth Service (Servicio asimétrico JWT externo)
- **Integraciones:** Mercado Pago SDK
- **Testing & QA:** JUnit 5, Testcontainers, k6

## Decisiones de Diseño Clave
* **Patrón Outbox:** Garantiza atomicidad estricta entre la confirmación de la reserva en base de datos relacional y la posterior emisión del evento hacia Kafka, impidiendo estados huérfanos.
* **Doble mecanismo de expiración:** Las retenciones vencidas se liberan mediante *Keyspace Notifications* de Redis (Push), respaldadas por un *Scheduler* de barrido periódico en DB para máxima resiliencia ante caídas temporales de caché.
* **Optimistic Locking:** Uso de `@Version` (JPA) + reintentos acotados para resolver colisiones de concurrencia extrema sobre un mismo recurso sin el costo de latencia de bloqueos pesimistas de BD.
* **Idempotencia de dos niveles:** Constraint `UNIQUE` en BD para deduplicar notificaciones repetidas de Mercado Pago, y validación por `eventId` en el consumidor Kafka para prevenir mensajes duplicados de red.
* **Reconciliación Resiliente:** Separación estricta entre errores transitorios (ej. timeout de BD, reintentables vía scheduler) y permanentes (ej. reserva inexistente, descartados inmediatamente).

## Contrato de eventos v1.1

Ratchet publica JSON en `reservation.events.v1` con `resourceId` como Kafka message key y entrega at-least-once. Los consumidores deben deduplicar globalmente por `eventId`.

```json
{
  "eventId": "uuid",
  "eventType": "RESERVATION_HOLD_CREATED",
  "eventVersion": 1,
  "occurredAt": "ISO-8601 UTC",
  "resourceId": "uuid",
  "holderRef": "string",
  "payload": {}
}
```

Los tipos publicados son `RESERVATION_HOLD_CREATED`, `RESERVATION_CONFIRMED`, `RESERVATION_RELEASED`, `RESERVATION_EXPIRED` y `RESERVATION_REJECTED`. El último representa exclusivamente un rechazo por `availableUnits <= 0` y no contiene `holdId`.

## Cómo Levantarlo

**Requisitos:** Docker, Docker Compose, JDK 21, Maven.

1. **Levantar infraestructura base:**
   ```bash
   cd infra
   docker compose up -d
   ```
   *(Nota: Levanta Redpanda en `localhost:9092` para clientes del host y `redpanda:29092` dentro de Docker, Redis en 6379, y PostgreSQL en 5432 auto-creando las bases `ratchet_db`, `payment_db`, y `notification_db`.)*

2. **Compilar el proyecto:**
   ```bash
   # ADVERTENCIA: Correr con la infra local APAGADA, ya que los tests levantan @EmbeddedKafka en el puerto 9092 y colisionarán con Redpanda. Si la infra está prendida, agregar -DskipTests.
   mvn clean install 
   ```

3. **Iniciar los microservicios:**
   Ejecutar mediante `mvn spring-boot:run` o ejecutando los JARs empaquetados en los siguientes puertos:
   - `inventory-service`: 8081
   - `reservation-service`: 8082
   - `payment-service`: 8083
   - `notification-service`: 8084

## Tests y Calidad
La suite de integración evita el uso de mocks frágiles utilizando **Testcontainers** para levantar réplicas efímeras de PostgreSQL, Redis y Kafka. El core de calidad está anclado en tests de estrés por hilos masivos (*concurrency tests*), garantizando matemáticamente que los invariantes de stock no se rompan por condiciones de carrera (Race Conditions).

## Resultados de Test de Carga
Auditado con escenarios progresivos utilizando **k6** (ver carpeta `/load-tests`). Bajo hardware local y dependencias en Docker:
- **Throughput:** ~77 req/sec sostenidos (creación de holds asíncronos).
- **VUs:** Soporte fluido para 200 Virtual Users concurrentes (cero cuellos de botella en el pool Hikari).
- **Latencias:** p95 anclado bajo los 110ms (incluyendo validación asimétrica de JWT cacheada localmente).
- **Fiabilidad:** 0.00% de errores de infraestructura o latencia. El 100% de los request fallidos durante la carga corresponden a respuestas HTTP 409 (comportamiento funcional esperado al agotarse las 10,000 unidades iniciales de prueba).
*(Nota: Estos números reflejan estabilidad arquitectónica local, no capacidad volumétrica de producción).*

## Fuera de Alcance de V1
- **Circuit Breakers:** No implementados para llamadas síncronas entre microservicios.
- **Seguridad inter-servicio (Deuda de Seguridad):** Los endpoints en `/internal/**` (incluyendo `InternalResourceController` y la consolidación de reservas) carecen de validación criptográfica y confían temporalmente en el aislamiento de la red Docker. Requieren mTLS o Auth Tokens antes de pasar a producción.
- **Entrega Outbox:** El publisher reintenta pendientes indefinidamente si Redpanda cae; la entrega es at-least-once y los consumidores deben deduplicar por `eventId`.
- **Módulo de Detección de Fraude:** Ausente en esta versión, dependiente en su totalidad del proveedor de pagos.
