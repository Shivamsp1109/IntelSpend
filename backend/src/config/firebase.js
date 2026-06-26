const admin = require('firebase-admin');

function loadServiceAccount() {
  if (process.env.FIREBASE_SERVICE_ACCOUNT_BASE64) {
    const json = Buffer
      .from(process.env.FIREBASE_SERVICE_ACCOUNT_BASE64, 'base64')
      .toString('utf8');
    return JSON.parse(json);
  }

  return undefined;
}

function initializeFirebase() {
  if (admin.apps.length > 0) return admin;

  const serviceAccount = loadServiceAccount();
  if (serviceAccount) {
    admin.initializeApp({
      credential: admin.credential.cert(serviceAccount)
    });
    return admin;
  }

  admin.initializeApp({
    credential: admin.credential.applicationDefault()
  });
  return admin;
}

module.exports = initializeFirebase();
