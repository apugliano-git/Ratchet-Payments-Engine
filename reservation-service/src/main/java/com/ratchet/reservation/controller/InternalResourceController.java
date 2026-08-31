package com.ratchet.reservation.controller;

import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.repository.ResourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/resources")
public class InternalResourceController {

    private final ResourceRepository resourceRepository;

    public InternalResourceController(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    public static class CreateResourceRequest {
        public Integer availableUnits;
        public UUID id; // opcional
    }

    // HERRAMIENTA DE TESTING - NO ES PARTE DE LA API DE NEGOCIO. Usado exclusivamente 
    // para setup de tests de carga (k6). Mismo riesgo de seguridad documentado que 
    // el resto de /internal/** (ver OPERATIONAL_AUDIT.md).
    @PostMapping
    public ResponseEntity<Resource> createResource(@RequestBody CreateResourceRequest request) {
        Resource r = new Resource();
        r.setId(request.id != null ? request.id : UUID.randomUUID());
        r.setAvailableUnits(request.availableUnits != null ? request.availableUnits : 10000);
        Resource saved = resourceRepository.save(r);
        return ResponseEntity.ok(saved);
    }
}
