package com.ratchet.reservation;

import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.exception.InvalidHoldStateException;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.repository.ResourceRepository;
import com.ratchet.reservation.service.HoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class HoldServiceTest {


    @Container
    static org.testcontainers.containers.GenericContainer<?> redis = new org.testcontainers.containers.GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private HoldService holdService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private HoldRepository holdRepository;

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
    void shouldCreateHoldAndDecrementResource() {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getHolderRef()).isEqualTo("ref-123");
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(9);
    }

    @Test
    void shouldReleaseHoldAndIncrementResource() {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.release(holdId);
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(10);
    }

    @Test
    void shouldConfirmHoldAndNotIncrementResource() {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.confirm(holdId);
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(9);
    }

    @Test
    void shouldExpireHoldAndIncrementResource() {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.expire(holdId);
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(10);
    }

    @Test
    void shouldThrowWhenInvalidStateTransition() {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.confirm(holdId); // Status is now CONFIRMED
        
        assertThatThrownBy(() -> holdService.release(holdId))
                .isInstanceOf(InvalidHoldStateException.class);
                
        assertThatThrownBy(() -> holdService.confirm(holdId))
                .isInstanceOf(InvalidHoldStateException.class);
                
        assertThatThrownBy(() -> holdService.expire(holdId))
                .isInstanceOf(InvalidHoldStateException.class);
                
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(9); // Unchanged
    }
}
