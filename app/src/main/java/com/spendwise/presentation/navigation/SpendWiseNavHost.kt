package com.spendwise.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.spendwise.presentation.screens.AddExpenseScreen
import com.spendwise.presentation.screens.AnalyticsScreen
import com.spendwise.presentation.screens.ExpenseListScreen
import com.spendwise.presentation.screens.HomeScreen
import com.spendwise.presentation.screens.LoginScreen
import com.spendwise.presentation.screens.NotificationsScreen
import com.spendwise.presentation.screens.ProfileScreen
import com.spendwise.presentation.screens.SplashScreen
import com.spendwise.presentation.viewmodel.AuthViewModel

@Composable
fun SpendWiseNavHost() {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()

    NavHost(
        navController = navController,
        startDestination = Routes.Splash.route
    ) {
        composable(Routes.Splash.route) {
            SplashScreen(
                authViewModel = authViewModel,
                onLoggedIn = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                },
                onLoggedOut = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(Routes.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Login.route) {
            LoginScreen(
                authViewModel = authViewModel,
                onLoggedIn = {
                    navController.navigate(Routes.Home.route) {
                        popUpTo(Routes.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Home.route) {
            HomeScreen(
                onAddExpense = { navController.navigate(Routes.AddExpense.route) },
                onViewExpenses = { navController.navigate(Routes.ExpenseList.route) },
                onAnalytics = { navController.navigate(Routes.Analytics.route) },
                onProfile = { navController.navigate(Routes.Profile.route) },
                onNotifications = { navController.navigate(Routes.Notifications.route) }
            )
        }
        composable(Routes.AddExpense.route) {
            AddExpenseScreen(onSaved = { navController.popBackStack() })
        }
        composable(Routes.ExpenseList.route) {
            ExpenseListScreen(
                onHome = { navController.navigate(Routes.Home.route) },
                onAddExpense = { navController.navigate(Routes.AddExpense.route) },
                onAnalytics = { navController.navigate(Routes.Analytics.route) },
                onProfile = { navController.navigate(Routes.Profile.route) }
            )
        }
        composable(Routes.Analytics.route) {
            AnalyticsScreen(
                onHome = { navController.navigate(Routes.Home.route) },
                onTransactions = { navController.navigate(Routes.ExpenseList.route) },
                onAddExpense = { navController.navigate(Routes.AddExpense.route) },
                onProfile = { navController.navigate(Routes.Profile.route) }
            )
        }
        composable(Routes.Profile.route) {
            ProfileScreen(
                authViewModel = authViewModel,
                onHome = { navController.navigate(Routes.Home.route) },
                onTransactions = { navController.navigate(Routes.ExpenseList.route) },
                onAddExpense = { navController.navigate(Routes.AddExpense.route) },
                onAnalytics = { navController.navigate(Routes.Analytics.route) },
                onLogout = {
                    navController.navigate(Routes.Login.route) {
                        popUpTo(Routes.Home.route) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.Notifications.route) {
            NotificationsScreen(onBack = { navController.popBackStack() })
        }
    }
}
