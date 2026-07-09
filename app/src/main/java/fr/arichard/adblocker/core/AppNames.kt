package fr.arichard.adblocker.core

import android.content.Context
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves and caches UID → app label / icon. Android's shared DNS resolver sends
 * most apps' queries itself, so attribution is only available when an app does its
 * own DNS (browsers and larger apps typically do); system UIDs are reported as such.
 */
object AppNames {

    private const val FIRST_APP_UID = 10_000
    private val labelCache = ConcurrentHashMap<Int, String>()   // "" = unresolvable
    private val packageCache = ConcurrentHashMap<Int, String>()

    /** Human-readable app name for [uid], or null when unknown. */
    fun label(context: Context, uid: Int): String? {
        if (uid <= 0) return null
        if (uid < FIRST_APP_UID) return context.getString(fr.arichard.adblocker.R.string.system_app)
        labelCache[uid]?.let { return it.ifEmpty { null } }

        val pm = context.packageManager
        val pkg = try {
            pm.getPackagesForUid(uid)?.firstOrNull()
        } catch (e: Exception) {
            null
        }
        val label = pkg?.let {
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
            } catch (e: Exception) {
                null
            }
        }
        labelCache[uid] = label ?: ""
        if (pkg != null) packageCache[uid] = pkg
        return label
    }

    /** Launcher icon for [uid]'s app, or null when unknown. Resolve label() first. */
    fun icon(context: Context, uid: Int): Drawable? {
        if (uid < FIRST_APP_UID) return null
        label(context, uid) // ensures packageCache is populated
        val pkg = packageCache[uid] ?: return null
        return try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }
    }
}
