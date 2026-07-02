require('dotenv').config();

const express = require('express');
const cors = require('cors');
const { assertDatabaseConnection } = require('./config/db');
const usersRouter = require('./routes/users');
const expensesRouter = require('./routes/expenses');
const incomesRouter = require('./routes/incomes');
const goalsRouter = require('./routes/goals');
const recurringRouter = require('./routes/recurring');

const app = express();
const port = Number(process.env.PORT || 3000);

app.use(cors());
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
