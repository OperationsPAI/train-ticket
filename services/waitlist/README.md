# waitlist

TypeScript waitlist service implementing priority-based queues and automatic promotion when capacity becomes available.

## Runtime

```bash
npm install
npm test
npm run build
node --import tsx src/main.ts
```

The service exposes `/health`, `/ready`, `/metadata`, and `/api/v1/waitlist/*` endpoints.
