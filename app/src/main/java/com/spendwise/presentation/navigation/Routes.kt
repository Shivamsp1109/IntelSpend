package com.spendwise.presentation.navigation

sealed class Routes(val route: String) {
    data object Splash : Routes("splash")
    data object Login : Routes("login")
    data object Home : Routes("home")
    data object AddExpense : Routes("add_expense")
    data object ExpenseList : Routes("expense_list")
    data object Analytics : Routes("analytics")
    data object Profile : Routes("profile")
    data object Notifications : Routes("notifications")
    
    // We can pass the URI as a string parameter if needed, but since it can be large,
    // a simple approach is to use a global/ViewModel or pass the encoded Uri.
    // For now, let's define it with parameters.
    data object Import : Routes("import?uri={uri}&name={name}&type={type}") {
        fun createRoute(uri: String, name: String, type: String) =
            "import?uri=${android.net.Uri.encode(uri)}&name=${android.net.Uri.encode(name)}&type=${android.net.Uri.encode(type)}"
    }
}
