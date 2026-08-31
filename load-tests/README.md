# Pruebas de Carga (k6)

Esta carpeta contiene scripts de pruebas de carga con [k6](https://k6.io/) para medir el rendimiento del flujo de reserva de recursos (`POST /holds`). 
Se han preparado dos escenarios para aislar y comparar el costo de obtener el JWT (autenticación delegada) frente al costo de solo validarlo localmente.

## 1. Instalación de k6

Si no tenés `k6` instalado en tu sistema:

### Linux (Debian/Ubuntu)
```bash
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6
```

### macOS (Homebrew)
```bash
brew install k6
```

## 2. Setup Previo (Requisitos para ambos escenarios)

Antes de correr los scripts, es necesario tener toda la infraestructura levantada:
- `docker compose up -d` en `infra/` para Postgres, Redis y Redpanda.
- `docker compose up -d` en el repositorio de Cypher (`Cypher_Auth_Service`) para que el proveedor OIDC esté disponible en `localhost:8080`.
- Correr los 4 servicios Java (`mvn spring-boot:run` en cada uno).

### Crear un Resource de Prueba

Ejecutá el siguiente CURL para crear un recurso con 10000 unidades en `reservation-service` (esto asegura que los holds no fallen por falta de stock):

```bash
curl -X POST http://localhost:8082/internal/resources \
  -H "Content-Type: application/json" \
  -d '{"availableUnits": 10000}'
```

Tomá nota del `id` (UUID) que te devuelva, ya que vas a necesitarlo como variable de entorno `RESOURCE_ID`.

### Setup en Cypher

Registrá un usuario de prueba en Cypher (solo se necesita hacer una vez):

```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email": "loadtest@example.com", "password": "Password123!"}'
```

## 3. Ejecución de Escenarios

Los escenarios configuran una rampa de carga progresiva:
- Sube a 10 VUs (30s), mantiene 10 VUs (1m)
- Sube a 50 VUs (30s), mantiene 50 VUs (1m)
- Sube a 200 VUs (30s), mantiene 200 VUs (1m)
- Baja a 0 VUs (30s)

Se aplican *thresholds* (umbrales) para verificar que el % de fallos reales (HTTP 5xx / timeouts) sea < 1%. 
> **Nota:** Los errores HTTP 409 (Conflicto por falta de stock) son resultados de negocio esperados y se contabilizan aparte bajo la métrica `http_409_conflicts`.

### Escenario A: Con Cypher Real (Setup de k6 hace login)

Este script hace un llamado a `POST /auth/login` de Cypher dentro del `setup()` de k6 para obtener un JWT fresco. Luego usa ese token para generar carga contra `reservation-service`.

```bash
k6 run \
  -e RESOURCE_ID=<TU_RESOURCE_ID> \
  -e CYPHER_EMAIL=loadtest@example.com \
  -e CYPHER_PASSWORD=Password123! \
  --out json=results-scenario-a.json \
  scenario-a-with-cypher.js
```

### Escenario B: Con JWT Pre-Generado (Solo validación local)

En este escenario, se asume que Cypher no es alcanzado durante la carga. El token se inyecta desde afuera.

1. Obtené un token manualmente:
```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email": "loadtest@example.com", "password": "Password123!"}'
```

2. Ejecutá la prueba pasándole el `access_token` como variable:
```bash
k6 run \
  -e RESOURCE_ID=<TU_RESOURCE_ID> \
  -e JWT_TOKEN=<TU_ACCESS_TOKEN> \
  --out json=results-scenario-b.json \
  scenario-b-pregenerated-jwt.js
```

## 4. Resultados a Esperar

Al finalizar, `k6` imprimirá un reporte en consola. Las métricas más relevantes a revisar son:
- `http_reqs`: Total de peticiones y throughput (reqs/s).
- `http_req_duration`: Latencia de las peticiones (prestar atención a `p(90)` y `p(95)`).
- `http_req_failed`: Porcentaje de peticiones que fallaron por timeouts o errores de servidor (excluye 409s).
- `http_409_conflicts`: Cantidad de peticiones rechazadas por falta de stock (solo si las reservas superan el `availableUnits`).
