package app.pixelpantry.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.pixelpantry.tv.ui.nav.NavGraph
import app.pixelpantry.tv.ui.theme.PantryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as PantryApp).container
        setContent {
            PantryTheme {
                NavGraph(container)
            }
        }
    }
}
