require('dotenv').config();

const express = require('express');
const cors = require('cors');
const { publicLimiter } = require('./middleware/rateLimit');
const { assertDatabaseConnection, assertSchemaIsCurrent } = require('./config/db');
const usersRouter = require('./routes/users');
const expensesRouter = require('./routes/expenses');
const incomesRouter = require('./routes/incomes');
const goalsRouter = require('./routes/goals');
const recurringRouter = require('./routes/recurring');
const budgetsRouter = require('./routes/budgets');
const assetsRouter = require('./routes/assets');
const loanDetailsRouter = require('./routes/loanDetails');
const financialStateRouter = require('./routes/financialState');
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

// Behind a reverse proxy or load balancer, req.ip is the proxy's address unless
// Express is told how many hops to look back through. Getting this wrong makes
// the IP limiter either useless (everyone shares the proxy's address) or
// spoofable (any client can claim any address via X-Forwarded-For), so it is
// explicit rather than guessed. Set TRUST_PROXY=1 when deploying behind one.
if (process.env.TRUST_PROXY) {
  app.set('trust proxy', Number(process.env.TRUST_PROXY));
}

app.use(cors({ origin: allowedOrigins.length > 0 ? allowedOrigins : false }));

// Coarse, pre-auth. The real per-user limits live on the routers, where the
// caller's identity is known — see middleware/rateLimit.js.
app.use(publicLimiter);
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
app.use('/budgets', budgetsRouter);
app.use('/assets', assetsRouter);
app.use('/loan-details', loanDetailsRouter);
app.use('/financial-state', financialStateRouter);
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
  await assertSchemaIsCurrent();
  app.listen(port, () => {
    console.log(`SpendWise backend running on port ${port}`);
  });
}

start().catch((error) => {
  console.error('Failed to start SpendWise backend:', error);
  process.exit(1);
});
