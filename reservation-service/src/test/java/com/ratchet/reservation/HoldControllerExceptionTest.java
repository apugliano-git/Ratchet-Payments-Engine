package com.ratchet.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratchet.reservation.controller.HoldController;
import com.ratchet.reservation.controller.HoldRequest;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.service.HoldService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HoldController.class)
class HoldControllerExceptionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private HoldService holdService;

    @MockBean
    private HoldRepository holdRepository;

    @Test
    void testOptimisticLockingFailureReturns409() throws Exception {
        UUID resourceId = UUID.randomUUID();
        HoldRequest request = new HoldRequest(resourceId, 15);

        Mockito.when(holdService.hold(Mockito.eq(resourceId), Mockito.eq("user-A"), Mockito.any(Duration.class)))
               .thenThrow(new ObjectOptimisticLockingFailureException("Resource", resourceId));

        mockMvc.perform(post("/holds")
                .with(jwt().jwt(jwt -> jwt.subject("user-A")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("El recurso está siendo modificado, intentá de nuevo"));
    }
}
