package com.spendwise

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.spendwise.presentation.navigation.SpendWiseNavHost
import com.spendwise.presentation.theme.SpendWiseTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Every screen in this app shows balances, merchants or statement data.
        // FLAG_SECURE keeps that out of screenshots, screen recordings and — the
        // one people forget — the Recents thumbnail, which the system writes to
        // disk and shows to anyone who picks the phone up.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            SpendWiseTheme {
                Surface {
                    SpendWiseNavHost()
                }
            }
        }
    }
}
