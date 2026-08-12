const express = require('express');
const { pool } = require('../config/db');
const { requireFirebaseAuth, requireSameUser } = require('../middleware/auth');

const router = express.Router();

router.get('/:uid', requireFirebaseAuth, requireSameUser, async (req, res, next) => {
  try {
    const [rows] = await pool.execute(
      `SELECT
        uid,
        name,
        email,
        gender,
        explicit_profile_image_url AS explicitProfileImageUrl,
        google_photo_url AS googlePhotoUrl,
        providers,
        updated_at AS updatedAt
      FROM users
      WHERE uid = ?`,
      [req.params.uid]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: 'User not found.' });
    }

    const user = rows[0];
    return res.json({
      ...user,
      providers: parseProviders(user.providers)
    });
  } catch (error) {
    return next(error);
  }
});

router.put('/:uid', requireFirebaseAuth, requireSameUser, async (req, res, next) => {
  try {
    const profile = req.body;
    const providers = JSON.stringify(Array.isArray(profile.providers) ? profile.providers : []);

    await pool.execute(
      `INSERT INTO users (
        uid,
        name,
        email,
        gender,
        explicit_profile_image_url,
        google_photo_url,
        providers,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)
      ON DUPLICATE KEY UPDATE
        name = VALUES(name),
        email = VALUES(email),
        gender = VALUES(gender),
        explicit_profile_image_url = VALUES(explicit_profile_image_url),
        google_photo_url = VALUES(google_photo_url),
        providers = VALUES(providers),
        updated_at = VALUES(updated_at)`,
      [
        req.params.uid,
        profile.name || '',
        req.user.email || profile.email || '',
        profile.gender || null,
        profile.explicitProfileImageUrl || null,
        profile.googlePhotoUrl || null,
        providers,
        Number(profile.updatedAt || Date.now())
      ]
    );

    return res.status(204).send();
  } catch (error) {
    return next(error);
  }
});

function parseProviders(value) {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  try {
    return JSON.parse(value);
  } catch {
    return [];
  }
}

module.exports = router;
