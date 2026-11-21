package sk.vespal.recshot

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Spoločné spustenie MainActivity z dlaždice (zatvorí lištu rýchlych nastavení). */
@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.launchMain(action: String, req: Int, cls: Class<*> = MainActivity::class.java) {
    val i = Intent(this, cls)
        .setAction(action)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        val pi = PendingIntent.getActivity(
            this, req, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pi)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(i)
    }
}

/** Dlaždica „Nahrať obrazovku“ – štart / stop nahrávania bez otvárania aplikácie. */
class RecordTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        val recording = RecorderService.state.value is RecState.Recording
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (recording) "Zastaviť nahrávanie" else getString(R.string.tile_record)
        tile.updateTile()
    }

    override fun onClick() {
        when (RecorderService.state.value) {
            is RecState.Recording, RecState.Starting -> {
                RecorderService.command(this, RecorderService.ACTION_STOP)
                qsTile?.let {
                    it.state = Tile.STATE_INACTIVE
                    it.label = getString(R.string.tile_record)
                    it.updateTile()
                }
            }
            else -> launchMain(MainActivity.ACTION_QUICK_RECORD, 100)
        }
    }
}

/** Dlaždica „Snímka obrazovky“. Počas nahrávania urobí snímku z bežiaceho záznamu. */
class ShotTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let {
            it.state = Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    override fun onClick() {
        val st = RecorderService.state.value
        if (st is RecState.Recording || st == RecState.ShotSession) {
            launchMain("shot", 101, ShotTrampolineActivity::class.java)
        } else {
            launchMain(MainActivity.ACTION_QUICK_SHOT, 102)
        }
    }
}
