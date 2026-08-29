package com.ratchet.reservation.service;

import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.exception.InsufficientAvailabilityException;
import com.ratchet.reservation.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class ResourceService {

    private final ResourceRepository resourceRepository;

    public ResourceService(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Transactional
    public void hold(UUID resourceId, String holderRef) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found"));

        if (resource.getAvailableUnits() <= 0) {
            throw new InsufficientAvailabilityException("No units available for resource: " + resourceId);
        }

        resource.setAvailableUnits(resource.getAvailableUnits() - 1);
        resourceRepository.save(resource);
    }
}
