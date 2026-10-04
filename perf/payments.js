// Load test for the payment API: run with `docker compose --profile perf run --rm k6`
import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const RATE = Number(__ENV.RATE || 20);

export const options = {
  scenarios: {
    // JIT and connection-pool warm-up, excluded from the thresholds
    warmup: {
      executor: 'constant-arrival-rate',
      rate: 5,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 5,
    },
    load: {
      executor: 'constant-arrival-rate',
      startTime: '15s',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '45s',
      preAllocatedVUs: RATE,
      maxVUs: RATE * 5,
    },
  },
  thresholds: {
    'http_req_failed{scenario:load}': ['rate<0.01'],
    'http_req_duration{scenario:load,endpoint:create}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{scenario:load,endpoint:retrieve}': ['p(95)<200'],
    'http_req_duration{scenario:load,endpoint:replay}': ['p(95)<200'],
    'checks{scenario:load}': ['rate>0.99'],
  },
};

export function setup() {
  const response = http.post(`${BASE_URL}/admin/merchants/k6-load-test/api-keys`, null, {
    headers: { 'X-Gateway-Admin-Key': __ENV.GATEWAY_ADMIN_API_KEY },
  });
  if (response.status !== 201) {
    fail(`Could not provision a merchant key: HTTP ${response.status}`);
  }
  return { apiKey: response.json('api_key') };
}

export default function (data) {
  // Last digit 1-9: odd is Authorized, even is Declined; 0 (bank error) is avoided
  const lastDigit = 1 + Math.floor(Math.random() * 9);
  const expectedStatus = lastDigit % 2 === 1 ? 'Authorized' : 'Declined';
  const params = {
    headers: {
      'X-API-Key': data.apiKey,
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
    },
  };
  const body = JSON.stringify({
    card_number: `222240534324887${lastDigit}`,
    expiry_month: 12,
    expiry_year: new Date().getUTCFullYear() + 1,
    currency: 'GBP',
    amount: 1 + Math.floor(Math.random() * 100000),
    cvv: '123',
  });

  const created = http.post(`${BASE_URL}/v1/payments`, body,
    { ...params, tags: { endpoint: 'create' } });
  const createdOk = check(created, {
    'payment created': (r) => r.status === 201,
    'bank outcome matches the card': (r) => r.json('status') === expectedStatus,
  });
  if (!createdOk) {
    return;
  }

  const fetched = http.get(`${BASE_URL}${created.headers['Location']}`,
    { headers: { 'X-API-Key': data.apiKey }, tags: { endpoint: 'retrieve' } });
  check(fetched, {
    'payment retrieved': (r) => r.status === 200 && r.json('id') === created.json('id'),
  });

  if (Math.random() < 0.1) {
    const replayed = http.post(`${BASE_URL}/v1/payments`, body,
      { ...params, tags: { endpoint: 'replay' } });
    check(replayed, {
      'retry returns the original payment': (r) => r.status === 201
        && r.json('id') === created.json('id'),
    });
  }
}
