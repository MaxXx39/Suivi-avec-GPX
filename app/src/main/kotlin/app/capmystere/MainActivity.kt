package app.capmystere

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.capmystere.core.model.TripCode
import app.capmystere.theme.ThemeRepository
import app.capmystere.ui.CapNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themes: ThemeRepository

    /** Code reçu par lien de partage (capmystere://join/CODE). */
    private val deepLinkCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) deepLinkCode.value = codeFrom(intent)
        setContent {
            CapNavHost(themes = themes, deepLinkCode = deepLinkCode.value, onDeepLinkConsumed = { deepLinkCode.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        codeFrom(intent)?.let { deepLinkCode.value = it }
    }

    private fun codeFrom(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString?.let(TripCode::fromLinkOrCode)
}
