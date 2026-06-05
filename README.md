# SpendWise

SpendWise is an offline-first expense tracker built with Kotlin, Jetpack Compose, MVVM, Clean Architecture, Hilt, Coroutines, StateFlow, Room, Firebase Auth, Firestore, and WorkManager.

## Architecture

```text
app/src/main/java/com/spendwise
├── data
│   ├── local
│   ├── remote
│   └── repository
├── domain
│   ├── model
│   ├── repository
│   └── usecase
├── presentation
│   ├── screens
│   ├── components
│   ├── navigation
│   └── viewmodel
├── di
└── util
```

## Implemented Features

- Splash login-state routing.
- Firebase email auth repository and Google ID-token auth entry point.
- Compose login, home, add expense, expense list, analytics, and profile screens.
- Room `ExpenseEntity` with Food, Travel, Shopping, Bills, Health, Entertainment, and Other categories.
- Expense CRUD with search, category filter, edit, and delete.
- StateFlow-powered ViewModels.
- Offline-first Room writes with WorkManager Firestore sync.
- Daily evening reminder notification.
- Monthly budget status and smart month-over-month insights.
- CSV and PDF report exporter utility.
- Unit test scaffolding for use cases and ViewModels with JUnit, Mockito, and coroutines test.

## Firebase Setup

1. Create a Firebase Android app with package `com.spendwise`.
2. Download `google-services.json`.
3. Place it at `app/google-services.json`.
4. Enable Email/Password and Google sign-in in Firebase Authentication.
5. Create a Firestore database.

The Google Services plugin is applied only when `app/google-services.json` exists so the project can still sync before Firebase is configured.

## Build

Open the project in Android Studio and sync Gradle. This workspace does not currently include a Gradle wrapper, so command-line builds require either Android Studio to generate one or a local Gradle installation.

Useful tasks after a wrapper exists:

```powershell
.\gradlew testDebugUnitTest
.\gradlew assembleDebug
```

## Suggested Commit Plan

```text
Initial Setup
Add Room Database
Add Expense CRUD
Add Firebase Auth
Add Analytics Screen
Implement WorkManager
Add Budget Tracking
```
