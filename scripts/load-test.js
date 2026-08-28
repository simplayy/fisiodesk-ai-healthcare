// k6: 50 professionisti concorrenti per 30 secondi sulla query target.
// docker run --rm --network host -v "$PWD/scripts:/scripts" grafana/k6 run /scripts/load-test.js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 50,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.01'],
  },
};

const base = __ENV.BASE_URL || 'http://localhost:8080';
const domande = [
  "Mostra pazienti con dolore lombare che hanno mostrato miglioramento negli ultimi 3 mesi ma hanno saltato l'ultimo appuntamento",
  'pazienti con cervicalgia migliorati negli ultimi 3 mesi',
  'chi ha il mal di schiena peggiorato nell\'ultimo mese?',
];
const professionisti = ['', '507f1f77bcf86cd799439021', '507f1f77bcf86cd799439022', '507f1f77bcf86cd799439023'];

export default function () {
  const body = JSON.stringify({
    domanda: domande[__ITER % domande.length],
    professionista_id: professionisti[__VU % professionisti.length],
  });
  const res = http.post(`${base}/api/ricerca`, body, { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'status 200': (r) => r.status === 200, 'ha risultati': (r) => Array.isArray(r.json('risultati')) });
}
