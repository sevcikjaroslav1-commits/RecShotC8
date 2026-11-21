package sk.vespal.recshot

import android.app.Activity
import android.os.Bundle

/**
 * Priehľadná aktivita spustená z notifikácie: systém pri jej otvorení zatvorí lištu notifikácií,
 * takže snímka počas nahrávania neobsahuje rozbalenú lištu.
 */
class ShotTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecorderService.command(this, RecorderService.ACTION_SHOT_DURING)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
