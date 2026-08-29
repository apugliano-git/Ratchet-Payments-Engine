# Changelog: Patrón Outbox e Integración Payment-Reservation

Este documento resume los cambios realizados en los últimos commits vinculados a la implementación del Patrón Outbox en `reservation-service` y la integración asíncrona de webhooks con `payment-service`.

## 1. Código y componentes nuevos agregados

### `reservation-service`
* **`OutboxEvent.java`**: Entidad JPA que representa un evento de negocio listo para ser publicado.
* **`OutboxRepository.java`**: Repositorio de Spring Data JPA para guardar y consultar los eventos a publicar.
* **`InternalHoldController.java`**: Nuevo controlador que expone `POST /internal/holds/{holdId}/confirm` (bypasseando autenticación JWT) para que `payment-service` pueda notificar un pago aprobado.
* **`SecurityConfig.java`**: Se actualizó para permitir acceso anónimo a los endpoints bajo el patrón `/internal/**`.
* **Modificación en `HoldService.java`**: Dentro del método de confirmación de reserva, se modificó para guardar el evento de `HoldConfirmed` en la tabla outbox dentro de la misma transacción de la base de datos (y la serialización JSON ocurre *antes* para preservar la integridad transaccional si esta falla).

### `payment-service`
* **`PaymentEvent.java` / `PaymentEventStatus.java` / `PaymentEventRepository.java`**: Modelo de datos y repositorio para persistir de manera resiliente el histórico de webhooks recibidos. Define un estado de ciclo de vida (`RECEIVED`, `PROCESSED`, `FAILED`). Incorpora bloqueo optimista JPA (`@Version`) para evitar corrupción de estado en modificaciones concurrentes.
* **`WebhookSignatureVerifier.java`**: Analiza y valida el signature HMAC-SHA256 (`x-signature`) enviado por Mercado Pago, e incorpora una ventana temporal de tolerancia (5 minutos) para evitar ataques de replay.
* **`PaymentWebhookController.java`**: Recibe el webhook, realiza validaciones (firma y ventana de tiempo), e implementa idempotencia devolviendo inmediatamente HTTP 200 ante duplicados. Si un webhook es válido pero no se puede extraer el `holdId`, marca el evento como `FAILED` inmediatamente para evitar un ciclo de reintentos huérfano. Además, incluye resolución activa de conflictos de concurrencia.
* **`ReservationConfirmationClient.java`**: Cliente HTTP (`RestTemplate`) encargado de avisarle a `reservation-service` sobre los pagos, capturando errores de red y de negocio. Retorna un `ConfirmationResult` enum (`SUCCESS`, `TRANSIENT_FAILURE`, `PERMANENT_FAILURE`).
* **`PaymentReconciliationScheduler.java`**: Tarea cron (`@Scheduled`) que corre en background cada 30 segundos, levanta los eventos que quedaron trabados en `RECEIVED`, y reintenta la confirmación. Al igual que el controller, implementa resolución activa de conflictos de estado.

## 2. Decisiones de negocio importantes

* **Patrón Outbox en lugar de publicar directo a Kafka**: 
  Si el servicio de reservas modificara el estado de la reserva y tratase de publicar en Kafka directamente, una caída de la red (o indisponibilidad de Kafka) tras guardar el estado en DB dejaría los sistemas inconsistentes. Enviar los eventos a una tabla dentro de la *misma transacción* relacional soluciona el problema de consistencia y garantiza que "si se confirmó la reserva, se encolará la notificación para despachar".
* **Separación de excepciones de negocio y de red al reconciliar**:
  La integración `payment-service -> reservation-service` puede fallar por problemas temporales (ej. un servicio caído o timeout) o problemas definitivos (ej. un Hold que expiró o fue cancelado antes del pago). El cliente HTTP diferencia esto y retorna `TRANSIENT_FAILURE` para los primeros y `PERMANENT_FAILURE` para los segundos, moviendo estos últimos a `FAILED` para evitar un loop de reintentos infinito inútil.
* **Ataques de Replay en Webhooks**: 
  Se añadió tolerancia de timestamp limitando la antigüedad del webhook (5 minutos de desviación permitida). Esto mitiga el riesgo de que atacantes intercepten peticiones válidas antiguas y las reenvíen repetidamente al servicio.
* **Idempotencia defensiva nativa**:
  En lugar de basarse solamente en consultas `SELECT ... EXISTS` antes de insertar (las cuales sufren de *race conditions* concurrentes), la protección de duplicidad descansa en una restricción de clave única de la base de datos (`external_event_id`). El código captura elegantemente la excepción `DataIntegrityViolationException` y responde exitosamente.
* **Resolución de conflictos y prioridad de SUCCESS**:
  Dado que el controlador procesa webhooks síncronamente y el scheduler procesa pendientes asíncronamente, puede darse una condición de carrera si ambos operan sobre el mismo evento al mismo tiempo. Al utilizar bloqueo optimista (`@Version`), uno de los dos fallará al intentar guardar. La regla de negocio estipula que un resultado `SUCCESS` (la reserva se confirmó) siempre gana sobre un resultado `FAILED` (ej. 409 arrojado por idempotencia del segundo intento). Si un hilo detecta conflicto concurrente, recarga el estado y fuerza la actualización a `PROCESSED` únicamente si su intento fue exitoso, previniendo perder la confirmación real de la reserva.

## 3. Registro histórico de commits

*(Este listado asume la finalización y confirmación del actual push)*
1. **`feat: Outbox pattern for HoldConfirmed event, Redpanda publisher scheduler, transactional integrity fix on payload serialization`** 
   - *Implementó todo el patrón Outbox en reservation-service y configuró el contenedor de Redpanda en el stack.*
2. **`feat: payment-service webhook idempotency, HMAC signature verification with replay protection window`** 
   - *Creación inicial de `payment-service` y lógica dura de webhooks: entidades, repositorio de eventos, validación HMAC de MercadoPago y protección de replay/idempotencia.*

## 4. Deuda Técnica de Seguridad Conocida

* **Falta de autenticación servicio-a-servicio**: El endpoint `/internal/**` en `reservation-service` no cuenta con autenticación. Se confía en que no está expuesto fuera de la red interna de Docker. Antes de cualquier despliegue real a producción, es imperativo implementar mTLS o un token compartido entre `payment-service` y `reservation-service`.
