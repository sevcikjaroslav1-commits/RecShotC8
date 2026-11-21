package sk.vespal.recshot

import android.content.Context

/** Nastavenia nahrávania uložené v SharedPreferences. */
data class RecSettings(
    /** Kratšia strana videa v px: 720, 1080, 1440 alebo 0 = natívne rozlíšenie displeja. */
    val resolution: Int = 1080,
    val fps: Int = 60,
    /** Zvuk: 0 = bez zvuku, 1 = mikrofón, 2 = systém (hry, videá), 3 = systém + mikrofón. */
    val audio: Int = 2,
    /** Odpočet pred štartom v sekundách. */
    val countdown: Int = 3,
    /** Zastaviť nahrávanie zatrasením telefónu. */
    val shakeToStop: Boolean = true,
) {
    val mic: Boolean get() = audio == 1 || audio == 3
    val system: Boolean get() = audio == 2 || audio == 3
    val anyAudio: Boolean get() = audio != 0
}

object Prefs {
    private const val FILE = "recshot"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): RecSettings {
        val p = sp(c)
        return RecSettings(
            resolution = p.getInt("resolution", 1080),
            fps = p.getInt("fps", 60),
            audio = p.getInt("audio", if (p.getBoolean("mic", false)) 1 else 2),
            countdown = p.getInt("countdown", 3),
            shakeToStop = p.getBoolean("shake", true),
        )
    }

    fun save(c: Context, s: RecSettings) {
        sp(c).edit()
            .putInt("resolution", s.resolution)
            .putInt("fps", s.fps)
            .putInt("audio", s.audio)
            .putInt("countdown", s.countdown)
            .putBoolean("shake", s.shakeToStop)
            .apply()
    }
}
