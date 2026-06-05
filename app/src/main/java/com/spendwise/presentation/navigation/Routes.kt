package com.spendwise.presentation.navigation

sealed class Routes(val route: String) {
    data object Splash : Routes("splash")
    data object Login : Routes("login")
    data object Home : Routes("home")
    data object AddExpense : Routes("add_expense")
    data object ExpenseList : Routes("expense_list")
    data object Analytics : Routes("analytics")
    data object Profile : Routes("profile")
}
