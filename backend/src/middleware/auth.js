const admin = require('../config/firebase');

async function requireFirebaseAuth(req, res, next) {
  const header = req.header('Authorization') || '';
  const [scheme, token] = header.split(' ');

  if (scheme !== 'Bearer' || !token) {
    return res.status(401).json({ error: 'Missing Firebase bearer token.' });
  }

  try {
    const decodedToken = await admin.auth().verifyIdToken(token);
    req.user = {
      uid: decodedToken.uid,
      email: decodedToken.email || ''
    };
    return next();
  } catch (error) {
    return res.status(401).json({ error: 'Invalid Firebase token.' });
  }
}

function requireSameUser(req, res, next) {
  const uid = req.params.uid || req.body.uid;
  if (!uid || uid !== req.user.uid) {
    return res.status(403).json({ error: 'You can only access your own data.' });
  }
  return next();
}

module.exports = {
  requireFirebaseAuth,
  requireSameUser
};
