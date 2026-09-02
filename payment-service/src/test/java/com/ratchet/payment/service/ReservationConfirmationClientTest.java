package com.ratchet.payment.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.util.ReflectionTestUtils.getField;

class ReservationConfirmationClientTest {

    @Test
    void postsToConfiguredReservationServicePort() {
        ReservationConfirmationClient client = new ReservationConfirmationClient(
                new RestTemplateBuilder(), "http://localhost:8082");
        RestTemplate restTemplate = (RestTemplate) getField(client, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        UUID holdId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8082/internal/holds/" + holdId + "/confirm"))
                .andExpect(method(POST))
                .andRespond(withSuccess());

        assertThat(client.confirmHold(holdId)).isEqualTo(ConfirmationResult.SUCCESS);
        server.verify();
    }
}
