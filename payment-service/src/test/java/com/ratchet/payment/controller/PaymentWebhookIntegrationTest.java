package com.ratchet.payment.controller;

import com.ratchet.payment.domain.PaymentEvent;
import com.ratchet.payment.domain.PaymentEventStatus;
import com.ratchet.payment.repository.PaymentEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PaymentWebhookIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("mercadopago.webhook.secret", () -> "test_secret");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @BeforeEach
    void setUp() {
        paymentEventRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        paymentEventRepository.deleteAll();
    }

    private String generateSignature(String dataId, String requestId, String ts, String secret) throws Exception {
        String manifest = String.format("id:%s;request-id:%s;ts:%s;", dataId, requestId, ts);
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] hash = sha256_HMAC.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return "ts=" + ts + ",v1=" + sb.toString();
    }

    @Test
    void shouldReturn401WhenSignatureIsInvalid() throws Exception {
        String payload = "{\"data\": {\"id\": \"evt-123\"}}";
        String invalidSignature = "ts=123,v1=invalidhash";

        mockMvc.perform(post("/webhooks/mercadopago")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("x-signature", invalidSignature)
                .header("x-request-id", "req-123"))
                .andExpect(status().isUnauthorized());

        assertThat(paymentEventRepository.findAll()).isEmpty();
    }

    @Test
    void shouldReturn401WhenTimestampIsTooOld() throws Exception {
        String payload = "{\"data\": {\"id\": \"evt-old-ts\"}}";
        // 6 minutes in the past
        String oldTs = String.valueOf(System.currentTimeMillis() - (6 * 60 * 1000));
        String reqId = "req-old-ts";
        String validSignatureForOldTs = generateSignature("evt-old-ts", reqId, oldTs, "test_secret");

        mockMvc.perform(post("/webhooks/mercadopago")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("x-signature", validSignatureForOldTs)
                .header("x-request-id", reqId))
                .andExpect(status().isUnauthorized());

        assertThat(paymentEventRepository.findAll()).isEmpty();
    }

    @Test
    void shouldReturn401WhenTimestampIsInTheFuture() throws Exception {
        String payload = "{\"data\": {\"id\": \"evt-future-ts\"}}";
        // 6 minutes in the future
        String futureTs = String.valueOf(System.currentTimeMillis() + (6 * 60 * 1000));
        String reqId = "req-future-ts";
        String validSignatureForFutureTs = generateSignature("evt-future-ts", reqId, futureTs, "test_secret");

        mockMvc.perform(post("/webhooks/mercadopago")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("x-signature", validSignatureForFutureTs)
                .header("x-request-id", reqId))
                .andExpect(status().isUnauthorized());

        assertThat(paymentEventRepository.findAll()).isEmpty();
    }

    @Test
    void shouldCreatePaymentEventWhenSignatureIsValid() throws Exception {
        String payload = "{\"data\": {\"id\": \"evt-valid-1\"}}";
        String ts = String.valueOf(System.currentTimeMillis());
        String reqId = "req-valid-1";
        String validSignature = generateSignature("evt-valid-1", reqId, ts, "test_secret");

        mockMvc.perform(post("/webhooks/mercadopago")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("x-signature", validSignature)
                .header("x-request-id", reqId))
                .andExpect(status().isOk());

        List<PaymentEvent> events = paymentEventRepository.findAll();
        assertThat(events).hasSize(1);
        PaymentEvent event = events.get(0);
        assertThat(event.getExternalEventId()).isEqualTo("evt-valid-1");
        assertThat(event.getStatus()).isEqualTo(PaymentEventStatus.RECEIVED);
        assertThat(event.getRawPayload()).isEqualTo(payload);
    }

    @Test
    void shouldReturn200AndNotDuplicateWhenEventAlreadyExists() throws Exception {
        // First request
        String payload = "{\"data\": {\"id\": \"evt-dup-1\"}}";
        String ts = String.valueOf(System.currentTimeMillis());
        String reqId = "req-dup-1";
        String validSignature = generateSignature("evt-dup-1", reqId, ts, "test_secret");

        mockMvc.perform(post("/webhooks/mercadopago")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("x-signature", validSignature)
                .header("x-request-id", reqId))
                .andExpect(status().isOk());

        assertThat(paymentEventRepository.findAll()).hasSize(1);

        // Second request with same ID
        mockMvc.perform(post("/webhooks/mercadopago")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("x-signature", validSignature)
                .header("x-request-id", reqId))
                .andExpect(status().isOk()); // Returns 200 to prevent retry

        // Still only 1 event
        assertThat(paymentEventRepository.findAll()).hasSize(1);
    }

    @Test
    void shouldHandleConcurrentRequestsViaDatabaseConstraint() throws Exception {
        String payload = "{\"data\": {\"id\": \"evt-concurrent\"}}";
        String ts = String.valueOf(System.currentTimeMillis());
        String reqId = "req-concurrent";
        String validSignature = generateSignature("evt-concurrent", reqId, ts, "test_secret");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    mockMvc.perform(post("/webhooks/mercadopago")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload)
                            .header("x-signature", validSignature)
                            .header("x-request-id", reqId))
                            .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown(); // start all threads at once
        done.await(); // wait for all to finish

        // Even with concurrency, only 1 event should be saved due to UNIQUE constraint
        assertThat(paymentEventRepository.findAll()).hasSize(1);
        executor.shutdown();
    }
}
