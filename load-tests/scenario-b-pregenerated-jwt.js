import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

export const options = {
    stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 10 },
        { duration: '30s', target: 50 },
        { duration: '1m', target: 50 },
        { duration: '30s', target: 200 },
        { duration: '1m', target: 200 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        'http_req_failed': ['rate<0.01'], // menos del 1% de fallos reales
    },
};

const conflictRate = new Rate('http_409_conflicts');

export function setup() {
    const jwt = __ENV.JWT_TOKEN;

    if (!jwt) {
        throw new Error('JWT_TOKEN must be provided');
    }

    // CREAMOS EL RECURSO AQUÍ PARA AISLAMIENTO
    const resourcePayload = JSON.stringify({ availableUnits: 10000 });
    const resourceParams = { headers: { 'Content-Type': 'application/json' } };
    const resourceRes = http.post('http://localhost:8082/internal/resources', resourcePayload, resourceParams);
    
    if (resourceRes.status !== 200 && resourceRes.status !== 201) {
        throw new Error(`Failed to create resource: ${resourceRes.status} ${resourceRes.body}`);
    }
    const resourceId = resourceRes.json('id');

    return { jwt, resourceId };
}

export default function (data) {
    const url = 'http://localhost:8082/holds';
    const payload = JSON.stringify({
        resourceId: data.resourceId,
        ttlMinutes: 15
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${data.jwt}`
        },
        responseCallback: http.expectedStatuses(200, 201, 409)
    };

    const res = http.post(url, payload, params);

    const isConflict = res.status === 409;
    conflictRate.add(isConflict);

    check(res, {
        'status is 200 or 409': (r) => r.status === 200 || r.status === 201 || r.status === 409,
    });

    sleep(1);
}
