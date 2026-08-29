package com.ratchet.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
public class WebhookSignatureVerifier {

    private final String webhookSecret;
    private final ObjectMapper objectMapper;

    public WebhookSignatureVerifier(
            @Value("${mercadopago.webhook.secret:placeholder_secret}") String webhookSecret,
            ObjectMapper objectMapper) {
        this.webhookSecret = webhookSecret;
        this.objectMapper = objectMapper;
    }

    public boolean verifySignature(String rawPayload, String xSignature, String xRequestId) {
        try {
            if (xSignature == null || xRequestId == null || rawPayload == null) {
                return false;
            }

            // Parse x-signature (e.g., ts=123,v1=hash)
            Map<String, String> signatureParts = parseSignatureHeader(xSignature);
            String tsStr = signatureParts.get("ts");
            String v1 = signatureParts.get("v1");

            if (tsStr == null || v1 == null) {
                return false;
            }

            // Check timestamp tolerance (5 minutes) to prevent replay attacks
            long ts;
            try {
                ts = Long.parseLong(tsStr);
            } catch (NumberFormatException e) {
                return false;
            }
            long currentTime = System.currentTimeMillis();
            long tolerance = 5 * 60 * 1000; // 5 minutes in milliseconds
            if (Math.abs(currentTime - ts) > tolerance) {
                return false;
            }

            // Parse payload to find ID
            String dataId = extractId(rawPayload);
            
            if (dataId == null) {
                return false;
            }

            // Build manifest: id:{dataId};request-id:{xRequestId};ts:{ts};
            String manifest = String.format("id:%s;request-id:%s;ts:%s;", dataId, xRequestId, tsStr);

            // Calculate HMAC-SHA256
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            byte[] hash = sha256_HMAC.doFinal(manifest.getBytes(StandardCharsets.UTF_8));
            String calculatedHash = bytesToHex(hash);

            return calculatedHash.equalsIgnoreCase(v1);

        } catch (Exception e) {
            return false;
        }
    }

    public String extractId(String rawPayload) {
        try {
            JsonNode rootNode = objectMapper.readTree(rawPayload);
            return extractId(rootNode);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractId(JsonNode rootNode) {
        if (rootNode.has("data") && rootNode.get("data").has("id")) {
            return rootNode.get("data").get("id").asText();
        } else if (rootNode.has("id")) {
            return rootNode.get("id").asText();
        }
        return null;
    }

    private Map<String, String> parseSignatureHeader(String header) {
        Map<String, String> map = new HashMap<>();
        String[] parts = header.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
