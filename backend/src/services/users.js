const { pool } = require('../config/db');

async function ensureUserExists(uid, email) {
  await pool.execute(
    `INSERT IGNORE INTO users (uid, name, email, providers, updated_at)
     VALUES (?, '', ?, CAST(? AS JSON), ?)`,
    [uid, email || '', JSON.stringify([]), Date.now()]
  );
}

module.exports = { ensureUserExists };
