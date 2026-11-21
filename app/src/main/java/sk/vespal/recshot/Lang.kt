package sk.vespal.recshot

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Jazyk aplikácie: "sk" alebo "en". Zmena okamžite prekreslí celé UI. */
object Lang {
    var code by mutableStateOf("sk")
        private set

    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val sp = ctx.getSharedPreferences("recshot", Context.MODE_PRIVATE)
        code = sp.getString("lang", null) ?: run {
            val sys = Locale.getDefault().language
            if (sys == "sk" || sys == "cs") "sk" else "en"
        }
    }

    fun set(ctx: Context, newCode: String) {
        code = newCode
        loaded = true
        ctx.getSharedPreferences("recshot", Context.MODE_PRIVATE).edit().putString("lang", newCode).apply()
    }
}

/** Preklad: slovenský a anglický text vedľa seba. */
fun tr(sk: String, en: String): String = if (Lang.code == "en") en else sk
