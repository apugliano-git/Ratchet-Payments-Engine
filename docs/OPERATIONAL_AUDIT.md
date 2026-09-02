# Auditoría Operacional (v1) — ACTUALIZADA

> **Última actualización:** 2026-08-30 — todos los bloqueantes originales resueltos.

---

## Mapa de Puertos (sin colisiones)

| Componente                  | Puerto Host | Puerto Interno | Notas |
|-----------------------------|-------------|----------------|-------|
| **inventory-service**       | 8081        | 8081           | Java (mvn spring-boot:run) |
| **reservation-service**     | 8082        | 8082           | Java (mvn spring-boot:run) |
| **payment-service**         | 8083        | 8083           | Java (mvn spring-boot:run) |
| **notification-service**    | 8084        | 8084           | Java (mvn spring-boot:run) — era 8083, corregido |
| **Postgres**                | 5432        | 5432           | Docker |
| **Redis**                   | 6379        | 6379           | Docker |
| **Redpanda (OUTSIDE)**      | 9092        | 9092           | Docker |
| **Redpanda (INTERNAL)**     | 29092       | 29092          | Docker network |

---

## 1. VARIABLES DE ENTORNO Y CONFIGURACIÓN

### `inventory-service`
- **Puerto:** `8081` ✅ Sin colisión
- **BD / Infra:** No requiere. Solo HTTP.

### `reservation-service`
- **Puerto:** `8082` ✅ Sin colisión
- **PostgreSQL:** `jdbc:postgresql://localhost:5432/ratchet_db` ✅ **RESUELTO** — datasource agregado
- **Redis:** `localhost:6379` ✅
- **Kafka API / Redpanda:** `KAFKA_BOOTSTRAP_SERVERS` con default `localhost:9092`; publica `reservation.events.v1` con key `resourceId`
- **JWT JWK URI:** Hardcodeado a `http://localhost:8080/.well-known/jwks.json` ⚠️ **Deuda técnica v1** — requiere Cypher/Keycloak local en 8080 para arrancar correctamente

### `payment-service`
- **Puerto:** `8083` ✅ Sin colisión
- **PostgreSQL:** `jdbc:postgresql://localhost:5432/payment_db` ✅ **RESUELTO** — nombre unificado
- **Credenciales:** `ratchet` / `ratchet_password` ✅ **RESUELTO** — unificadas
- **MP_WEBHOOK_SECRET:** `${MP_WEBHOOK_SECRET:placeholder_secret}` ⚠️ **Deuda técnica v1** — inyectar antes de prod
- **Reservation URL:** `RESERVATION_SERVICE_URL` con default `http://localhost:8082`

### `notification-service`
- **Puerto:** `8084` ✅ **RESUELTO** — era 8083
- **PostgreSQL:** `jdbc:postgresql://localhost:5432/notification_db` ✅
- **Credenciales:** `ratchet` / `ratchet_password` ✅ **RESUELTO** — unificadas
- **Kafka API / Redpanda:** `KAFKA_BOOTSTRAP_SERVERS` con default `localhost:9092`; consume `reservation.events.v1` con group `notification-service-group`

---

## 2. DOCKER Y DEPENDENCIAS DE INFRAESTRUCTURA

### Estado de `infra/docker-compose.yml`
- **Postgres 16** → puerto `5432` ✅
- **Redis 7** → puerto `6379` ✅
- **Redpanda (`docker.redpanda.com/redpandadata/redpanda:v24.2.7`)** → `9092` externo y `29092` interno ✅

> **Nota:** Redpanda expone la API compatible con Kafka. Ratchet v1.1 usa JSON textual y no depende de Avro ni Schema Registry.

### Inicialización de Bases de Datos
- `infra/init-db/01-create-databases.sql` ✅ **RESUELTO** — crea `payment_db` y `notification_db` automáticamente al primer arranque del volumen de Postgres
- Las 3 BDs existen y son accesibles con el usuario `ratchet`

### Servicios Java no dockerizados
- Los 4 servicios Java siguen sin estar en el docker-compose. Se ejecutan con `mvn spring-boot:run`.
- ⚠️ **Deuda técnica v1** — documentado explícitamente en cada `application.properties` con un comentario de "NOTA DE EJECUCIÓN LOCAL"

### `api.version=1.41` en Surefire
- **reservation-service:** ✅ Configurado con comentario explicativo
- **payment-service:** ✅ Configurado
- **notification-service:** ✅ Configurado
- **inventory-service:** ✅ No tiene tests de Testcontainers; no aplica

---

## 3. CREDENCIALES Y SECRETS

| Secret / Valor             | Archivo                                                      | Estado |
|----------------------------|--------------------------------------------------------------|--------|
| `POSTGRES_USER=ratchet`    | `infra/docker-compose.yml`                                   | ⚠️ Hardcodeado para dev local — mover a secrets en prod |
| `POSTGRES_PASSWORD=ratchet_password` | `infra/docker-compose.yml`                         | ⚠️ Hardcodeado para dev local |
| `spring.datasource.password=ratchet_password` | `*/application.properties` (3 servicios)  | ⚠️ Hardcodeado para dev local |
| `MP_WEBHOOK_SECRET`        | `payment-service/src/main/resources/application.properties`  | ⚠️ Placeholder — **inyectar antes de producción** |
| `jwk-set-uri`              | `reservation-service/src/main/resources/application.properties` | ⚠️ Apunta a `localhost:8080` — reemplazar con IdP real |

---

## 4. INCONSISTENCIAS ENTRE MÓDULOS — RESUELTO

### Credenciales y nombres de BD
- ✅ **RESUELTO** — todos los servicios usan `ratchet` / `ratchet_password`
- ✅ **RESUELTO** — BDs: `ratchet_db` (reservation), `payment_db` (payment), `notification_db` (notification)

### `pom.xml` — estado
- `inventory-service`: mínimo (solo spring-boot-starter-web) — ⚠️ servicio incompleto (deuda conocida)
- `reservation`, `payment`, `notification`: alineados

---

## 5. LOGS Y ARCHIVOS SUELTOS

- **Git status:** ✅ Limpio (excepto los cambios de esta auditoría, pendientes de commit)
- **Archivos temporales/scripts sueltos:** ✅ Ninguno
- **.gitignore:** ✅ Cubre `target/`, `*.log`, `*.class`. Agregado `.env`, `.env.local`, `.env.*.local`

---

## Resultado de Validación Final (2026-08-30)

### `mvn clean install` — Monorepo completo
```
[INFO] Reactor Summary for Ratchet Payments Engine 1.0.0-SNAPSHOT:
[INFO] Ratchet Payments Engine ............................ SUCCESS [  0.568 s]
[INFO] inventory-service .................................. SUCCESS [  4.251 s]
[INFO] reservation-service ................................ SUCCESS [02:17 min]  -- Tests run: 36, Failures: 0, Errors: 0
[INFO] payment-service .................................... SUCCESS [ 37.448 s]
[INFO] notification-service ............................... SUCCESS [ 58.850 s]  -- Tests run: 4, Failures: 0, Errors: 0
[INFO] BUILD SUCCESS
```

### Arranque real de los 4 servicios (con `docker compose up -d` + `mvn spring-boot:run`)

| Servicio             | Resultado | Log clave |
|----------------------|-----------|-----------|
| **inventory-service**    | ✅ OK | `Tomcat started on port 8081` |
| **reservation-service**  | ✅ OK | `HikariPool-1 - Start completed` · `Tomcat started on port 8082` |
| **payment-service**      | ✅ OK | `HikariPool-1 - Start completed` · `Tomcat started on port 8083` |
| **notification-service** | ✅ OK | `HikariPool-1 - Start completed` · `Tomcat started on port 8084` |

---

## 6. LIMITACIONES CONOCIDAS DE TESTING

### Conflicto de puerto entre `@EmbeddedKafka` y Redpanda

**Síntoma:** `OutboxIntegrationTest` falla con el siguiente error al correr `mvn clean install` mientras el Docker Compose está levantado:

```
Unable to start acceptor for ListenerName(PLAINTEXT)
org.apache.kafka.common.KafkaException: Socket server failed to bind to localhost:9092: La dirección ya se está usando.
```

**Causa:** `OutboxIntegrationTest` usa la anotación `@EmbeddedKafka`, que levanta un broker Kafka embebido en el proceso de tests y lo bindea al puerto `9092` del host. Si Redpanda ya está corriendo y también tiene mapeado el puerto `9092` al host (como está configurado en `infra/docker-compose.yml`), ambos procesos intentan usar el mismo puerto y el test falla.

Este error **no es un bug del código** — el test y la infra son correctos por separado. Es un conflicto de entorno de ejecución.

**Regla de oro para desarrollo local:**

> ⚠️ **No correr `mvn clean install` con `docker compose up` activo al mismo tiempo.**

Las dos opciones válidas son:

**Opción A (recomendada):** Bajar la infra antes de buildear, y levantarla después.

```bash
# 1. Bajar la infra si está corriendo
cd infra && docker compose down

# 2. Correr el build completo (tests incluidos)
cd .. && mvn clean install

# 3. Levantar la infra para ejecutar los servicios
cd infra && docker compose up -d
```

**Opción B:** Buildear sin tests si la infra ya está levantada y no se puede bajar.

```bash
mvn clean install -DskipTests
```

**Contexto adicional:** Este conflicto aplica específicamente a `reservation-service/OutboxIntegrationTest` porque es el único test de la suite que usa `@EmbeddedKafka` (en lugar de Testcontainers). Los tests de `payment-service` y `notification-service` usan Testcontainers con puertos aleatorios y **no tienen este problema** — pueden correr con la infra levantada sin inconvenientes.


## 7. DEUDA TÉCNICA DE SEGURIDAD Y ENDPOINTS INTERNOS

**Riesgo Conocido:** Los endpoints bajo el path `/internal/**` carecen de autenticación de servicio-a-servicio (mTLS o tokens JWT compartidos). Actualmente confían exclusivamente en el aislamiento de la red interna de Docker.

**Endpoints afectados:**
1. `POST /internal/holds/{holdId}/confirm` en `reservation-service`: Expuesto para que `payment-service` consolide reservas.
2. `POST /internal/resources` en `reservation-service` (`InternalResourceController`): **Herramienta de testing** expuesta para inyectar inventario inicial (`availableUnits` arbitrario) durante los tests de carga con k6. 

**Mitigación requerida para producción:**
Cualquier despliegue fuera del entorno local seguro debe proteger estas rutas mediante autenticación mutua (mTLS), bloquear el acceso público en el Ingress/API Gateway de forma explícita, o bien requerir un JWT de servicio autorizado. La herramienta de testing (`InternalResourceController`) debe eliminarse o inhabilitarse condicionalmente en perfiles productivos.
