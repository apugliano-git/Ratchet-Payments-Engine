package com.ratchet.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratchet.reservation.controller.HoldRequest;
import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.repository.ResourceRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HoldControllerSecurityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @org.springframework.boot.test.mock.mockito.MockBean
    private org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;

    private UUID resourceId;

    @BeforeEach
    void setUp() {
        holdRepository.deleteAll();
        resourceRepository.deleteAll();
        
        Resource resource = new Resource();
        resourceId = UUID.randomUUID();
        resource.setId(resourceId);
        resource.setAvailableUnits(10);
        resourceRepository.save(resource);
    }

    @AfterEach
    void tearDown() {
        holdRepository.deleteAll();
        resourceRepository.deleteAll();
    }

    @Test
    void testGetHealthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }

    @Test
    void testCreateHoldWithoutTokenIsUnauthorized() throws Exception {
        HoldRequest request = new HoldRequest(resourceId, 15);
        mockMvc.perform(post("/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testCreateHoldWithTokenWorks() throws Exception {
        HoldRequest request = new HoldRequest(resourceId, 15);
        MvcResult result = mockMvc.perform(post("/holds")
                .with(jwt().jwt(jwt -> jwt.subject("user-A")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
                
        String responseBody = result.getResponse().getContentAsString();
        Map<?, ?> map = objectMapper.readValue(responseBody, Map.class);
        UUID holdId = UUID.fromString((String) map.get("holdId"));
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getHolderRef()).isEqualTo("user-A");
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }
    
    @Test
    void testReleaseHoldWithDifferentUserIsForbidden() throws Exception {
        // user-A creates hold
        HoldRequest request = new HoldRequest(resourceId, 15);
        MvcResult result = mockMvc.perform(post("/holds")
                .with(jwt().jwt(jwt -> jwt.subject("user-A")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
                
        String responseBody = result.getResponse().getContentAsString();
        Map<?, ?> map = objectMapper.readValue(responseBody, Map.class);
        UUID holdId = UUID.fromString((String) map.get("holdId"));
        
        // user-B tries to release it
        mockMvc.perform(post("/holds/" + holdId + "/release")
                .with(jwt().jwt(jwt -> jwt.subject("user-B"))))
                .andExpect(status().isForbidden());
                
        // Hold should still be ACTIVE
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }
    
    @Test
    void testReleaseHoldWithSameUserWorks() throws Exception {
        // user-A creates hold
        HoldRequest request = new HoldRequest(resourceId, 15);
        MvcResult result = mockMvc.perform(post("/holds")
                .with(jwt().jwt(jwt -> jwt.subject("user-A")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
                
        String responseBody = result.getResponse().getContentAsString();
        Map<?, ?> map = objectMapper.readValue(responseBody, Map.class);
        UUID holdId = UUID.fromString((String) map.get("holdId"));
        
        // user-A releases it
        mockMvc.perform(post("/holds/" + holdId + "/release")
                .with(jwt().jwt(jwt -> jwt.subject("user-A"))))
                .andExpect(status().isOk());
                
        // Hold should be RELEASED
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }
}
