require('dotenv').config();

const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const { assertDatabaseConnection } = require('./config/db');
const usersRouter = require('./routes/users');
const expensesRouter = require('./routes/expenses');
const incomesRouter = require('./routes/incomes');
const goalsRouter = require('./routes/goals');
const recurringRouter = require('./routes/recurring');
const extractRouter = require('./routes/extract');

const app = express();
const port = Number(process.env.PORT || 3000);

// This API is consumed by the native Android app (no browser client), so the
// allowlist is empty by default — set ALLOWED_ORIGINS to a comma-separated
// list of origins if a browser-based client is ever added.
const allowedOrigins = (process.env.ALLOWED_ORIGINS || '')
  .split(',')
  .map((origin) => origin.trim())
  .filter(Boolean);

app.use(cors({ origin: allowedOrigins.length > 0 ? allowedOrigins : false }));
app.use(rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 300,
  standardHeaders: true,
  legacyHeaders: false
}));
// Sync payloads are small; only the extraction route carries images, so it gets
// its own larger ceiling rather than raising the limit for every endpoint.
app.use('/extract', express.json({ limit: '8mb' }));
app.use(express.json({ limit: '2mb' }));
app.use((req, res, next) => {
  console.log(`${new Date().toISOString()} ${req.method} ${req.originalUrl}`);
  next();
});

app.get('/health', (req, res) => {
  res.json({ status: 'ok', service: 'spendwise-backend' });
});

app.use('/users', usersRouter);
app.use('/expenses', expensesRouter);
app.use('/incomes', incomesRouter);
app.use('/goals', goalsRouter);
app.use('/recurring', recurringRouter);
app.use('/extract', extractRouter);


app.use((error, req, res, next) => {
  const status = error.status || 500;
  const message = status === 500 ? 'Internal server error.' : error.message;
  if (status === 500) {
    console.error(error);
  }
  res.status(status).json({ error: message });
});

async function start() {
  await assertDatabaseConnection();
  app.listen(port, () => {
    console.log(`SpendWise backend running on port ${port}`);
  });
}

start().catch((error) => {
  console.error('Failed to start SpendWise backend:', error);
  process.exit(1);
});
