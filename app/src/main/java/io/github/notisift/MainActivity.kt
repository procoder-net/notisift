package io.github.notisift

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.notisift.engine.Category
import io.github.notisift.engine.Tier
import io.github.notisift.ui.SiftRoot
import io.github.notisift.ui.SiftTheme

class MainActivity : ComponentActivity() {
    /** Tier (and group) requested by tapping a drawer notification. */
    private val requestedTier = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedTier.value = request(intent)
        setContent {
            SiftTheme {
                SiftRoot(requestedTier.value, onTierShown = { requestedTier.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTier.value = request(intent) ?: ""
    }

    /**
     * "tier" or "tier/category" from a drawer tap. This activity is exported (it's the launcher entry), so any
     * app can send these extras: only known tier and group ids get through.
     */
    private fun request(intent: Intent): String? {
        val tier = Tier.fromId(intent.getStringExtra(EXTRA_TIER)) ?: return null
        val category = intent.getStringExtra(EXTRA_CATEGORY)?.let { id -> Category.entries.firstOrNull { it.id == id } }
        return category?.let { "${tier.id}/${it.id}" } ?: tier.id
    }

    companion object {
        const val EXTRA_TIER = "tier"
        const val EXTRA_CATEGORY = "category"
    }
}
