import http from 'k6/http';
import { check } from 'k6';

const rate = Number(__ENV.RATE || 10);
const duration = __ENV.DURATION || '60s';
const url = (__ENV.ORDER_URL || 'http://localhost:8081') + '/orders';
const params = { headers: { 'Content-Type': 'application/json' } };

// Open-loop load: a fixed arrival rate, so slow responses do not slow the sender down.
export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: rate,
      timeUnit: '1s',
      duration: duration,
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const body = JSON.stringify({
    customerId: `load-${__VU}-${__ITER}`,
    customerRegion: 'US',
    currency: 'USD',
    items: [{ sku: 'load-1', quantity: 1, unitPrice: 10.0 }],
  });
  const res = http.post(url, body, params);
  check(res, { 'status is 201': (r) => r.status === 201 });
}
