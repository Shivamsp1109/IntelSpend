# SpendWise

SpendWise is an offline-first expense tracker built with Kotlin, Jetpack Compose, MVVM, Clean Architecture, Hilt, Coroutines, StateFlow, Room, Firebase Auth, MySQL API sync, and WorkManager.

## Implemented Features

- Splash login-state routing.
- Firebase Email/Password and Google sign-in.
- Compose login, home, add expense, expense list, analytics, notifications, and profile screens.
- Room `ExpenseEntity` with Food, Travel, Shopping, Bills, Health, Entertainment, and Other categories.
- Expense CRUD with search, category filter, edit, delete, and Paging 3.
- StateFlow-powered ViewModels.
- Offline-first Room writes with WorkManager sync.
- Daily evening reminder notification.
- Monthly income and budget tracking.
- Smart month-over-month spending insights.
- CSV and PDF report exporter utility.
- Unit test scaffolding for repositories, use cases, and ViewModels.

## Completed Work

- Add Firebase Auth
- Add Analytics Screen
- Implement WorkManager
- Add Budget Tracking

## How to start MySQL Backend
- cd "C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64"
- .\bin\mysqld.exe --console --port=3307 --basedir="C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64" --datadir="C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64\data"

## Next MySQL start
- cd "C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64"
- & "C:\Users\Shivam\Downloads\mysql-8.0.46-winx64\mysql-8.0.46-winx64\bin\mysql.exe" -h 127.0.0.1 -P 3307 -u spendwise_user -p
Now write the password
