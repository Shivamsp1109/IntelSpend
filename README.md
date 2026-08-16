# IntelSpend

IntelSpend is an offline-first Android personal finance application for tracking expenses, income, budgets, goals, and recurring commitments. It combines encrypted local storage with authenticated cloud synchronization and can extract transactions from bank statements, spreadsheets, receipts, and payment screenshots.

## Highlights

- Offline-first expense and income management with search, filtering, editing, deletion, and Paging 3.
- Monthly budgets, category-level budget limits, savings goals, and progress tracking.
- Spending analytics with comparisons, category breakdowns, heatmaps, and smart insights.
- Automatic detection and management of recurring payments, including due-date reminders.
- CSV and PDF report export.
- Firebase email/password and Google authentication.
- Background synchronization and notifications powered by WorkManager.

## Transaction Import

IntelSpend supports transaction extraction from:

- CSV and XLSX spreadsheets.
- Text-based and scanned PDF bank statements, including password-protected PDFs.
- Receipts and payment screenshots through ML Kit OCR.

The ingestion pipeline performs document classification, field normalization, confidence scoring, merchant cleanup, duplicate detection, and automatic categorization. When smart extraction is enabled, uncertain results can be enriched remotely while retaining an on-device fallback for network or quota failures.

## Architecture and Data

- Kotlin and Jetpack Compose with Material 3.
- MVVM and Clean Architecture with repository and use-case layers.
- Hilt dependency injection, Coroutines, and StateFlow.
- SQLCipher-encrypted Room database with versioned migrations.
- Local-first writes with pending-update and deletion queues.
- Retrofit/OkHttp communication with a Node.js and Express REST API backed by MySQL.
- Firebase ID-token verification for protected backend operations.
- Network-constrained WorkManager jobs for retryable synchronization.

## Project Structure

```text
app/src/main/java/com/spendwise/
|-- data/          Room entities and DAOs, repositories, remote APIs, ingestion
|-- domain/        Models, repository contracts, and use cases
|-- presentation/  Compose screens, reusable components, navigation, ViewModels
|-- di/            Hilt dependency-injection modules
`-- util/          Synchronization, notifications, exports, security, and metrics

backend/
|-- src/routes/    User, expense, income, goal, recurring, and extraction APIs
|-- src/services/  Categorization, extraction, pagination, and user services
|-- src/middleware Authentication and rate limiting
`-- schema.sql     MySQL schema
```

## Testing

The project includes unit and instrumentation coverage for domain models, use cases, ViewModels, ingestion readers and extractors, duplicate detection, categorization, report generation, and Compose components. Regression fixtures cover CSV, XLSX, PDF-statement, receipt, and screenshot edge cases.

```powershell
.\gradlew.bat test
cd backend
npm test
```

The feature-scale test exercises 10,000 transactions, 500 categories, and a 50 MB report payload.

## How to start MySQL Backend
- cd "C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64"
- .\bin\mysqld.exe --console --port=3307 --basedir="C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64" --datadir="C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64\data"

## Next MySQL start
- cd "C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64"
- & "C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64\bin\mysql.exe" -h 127.0.0.1 -P 3307 -u spendwise_user -p
Now write the password
