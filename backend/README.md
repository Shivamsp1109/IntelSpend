# SpendWise Backend

Node.js + Express API that verifies Firebase users and writes SpendWise data to MySQL.

## Setup

1. Install dependencies:

```powershell
cd backend
npm install
```

2. Create MySQL database and tables:

```powershell
mysql -u root -p < schema.sql
```

3. Create Firebase Admin service account:

Firebase Console:

```text
Project Settings > Service accounts > Generate new private key
```

Save the JSON file in `backend/serviceAccountKey.json`.

4. Create `.env`:

```powershell
copy .env.example .env
```

Update MySQL password and Firebase credential path.

5. Start server:

```powershell
npm start
```

6. Test health:

```text
http://localhost:3000/health
```

## Android Base URL

For Android emulator:

```kotlin
buildConfigField("String", "MYSQL_API_BASE_URL", "\"http://10.0.2.2:3000/\"")
```

For physical phone on same Wi-Fi:

```kotlin
buildConfigField("String", "MYSQL_API_BASE_URL", "\"http://YOUR_LAPTOP_IP:3000/\"")
```

## Endpoints

All protected endpoints require:

```text
Authorization: Bearer <Firebase ID token>
```

Endpoints:

```text
GET  /users/:uid
PUT  /users/:uid
POST /expenses/sync
```

The backend rejects requests where the Firebase token UID does not match the requested/body UID.
