package fr.arichard.adblocker.core

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Holds the set of blocked domains in memory and knows how to (re)load it from the
 * bundled asset, a downloaded hosts file, and the user's custom rules.
 *
 * Matching is suffix based: blocking "doubleclick.net" also blocks "ads.doubleclick.net".
 * The allow list wins over the block list.
 */
object BlocklistManager {

    private const val TAG = "BlocklistManager"
    private const val HOSTS_FILE = "hosts_downloaded.txt"
    private const val MIN_VALID_DOWNLOAD_BYTES = 1024L

    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()

    @Volatile var domainCount: Int = 0
        private set

    @Volatile var isLoaded: Boolean = false
        private set

    /** Loads the blocklist synchronously. Call from a background thread. */
    @Synchronized
    fun load(context: Context) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        val set = HashSet<String>(131_072)

        val downloaded = File(appContext.filesDir, HOSTS_FILE)
        var source = "bundled"
        try {
            if (downloaded.isFile && downloaded.length() > MIN_VALID_DOWNLOAD_BYTES) {
                downloaded.inputStream().use { parseHosts(it, set) }
                source = "downloaded"
            } else {
                appContext.assets.open("hosts.txt").use { parseHosts(it, set) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load hosts file, falling back to bundled asset", e)
            try {
                set.clear()
                appContext.assets.open("hosts.txt").use { parseHosts(it, set) }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to load bundled hosts asset", e2)
            }
        }

        set.addAll(prefs.customBlocked)
        val allowSet = prefs.customAllowed.toHashSet()

        blocked = set
        allowed = allowSet
        domainCount = set.size
        isLoaded = true
        Log.i(TAG, "Loaded $source blocklist: ${set.size} domains, ${allowSet.size} allowed")
    }

    fun ensureLoaded(context: Context) {
        if (!isLoaded) load(context)
    }

    /** True when [domain] or any of its parent domains is on the block list. */
    fun isBlocked(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val d = domain.lowercase().trimEnd('.')
        if (matchesSuffix(allowed, d)) return false
        return matchesSuffix(blocked, d)
    }

    private fun matchesSuffix(set: Set<String>, domain: String): Boolean {
        if (set.isEmpty()) return false
        var cur = domain
        while (true) {
            if (cur in set) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
        }
    }

    /**
     * Downloads a fresh hosts file from [url] and reloads the in-memory set.
     * Returns null on success, or a short error description.
     * Call from a background thread.
     */
    fun refresh(context: Context, url: String): String? {
        val appContext = context.applicationContext
        val tmp = File(appContext.filesDir, "$HOSTS_FILE.tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "AdBlocker4Android/1.0")
            try {
                if (connection.responseCode !in 200..299) {
                    return "HTTP ${connection.responseCode}"
                }
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }

            if (tmp.length() < MIN_VALID_DOWNLOAD_BYTES) {
                return "Downloaded file is too small"
            }
            // Sanity check: the file must actually contain parseable entries.
            val probe = HashSet<String>(1024)
            tmp.inputStream().use { parseHosts(it, probe, limit = 2000) }
            if (probe.isEmpty()) {
                return "Downloaded file contains no host entries"
            }

            val target = File(appContext.filesDir, HOSTS_FILE)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) return "Could not save blocklist"
            }
            Prefs(appContext).lastRefreshMillis = System.currentTimeMillis()
            load(appContext)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Blocklist refresh failed", e)
            return e.message ?: e.javaClass.simpleName
        } finally {
            tmp.delete()
        }
    }

    /** Test seam: installs the rule sets directly, bypassing file/asset loading. */
    internal fun setRulesForTest(blockedSet: Set<String>, allowedSet: Set<String>) {
        blocked = blockedSet
        allowed = allowedSet
        domainCount = blockedSet.size
        isLoaded = true
    }

    /**
     * Parses hosts-file syntax ("0.0.0.0 domain") as well as plain domain-per-line lists.
     */
    internal fun parseHosts(input: InputStream, into: MutableSet<String>, limit: Int = Int.MAX_VALUE) {
        val skip = setOf(
            "localhost", "localhost.localdomain", "local", "broadcasthost",
            "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
            "ip6-allnodes", "ip6-allrouters", "ip6-allhosts", "0.0.0.0"
        )
        BufferedReader(input.reader(), 1 shl 16).useLines { lines ->
            for (rawLine in lines) {
                if (into.size >= limit) return
                var line = rawLine
                val hash = line.indexOf('#')
                if (hash >= 0) line = line.substring(0, hash)
                line = line.trim()
                if (line.isEmpty()) continue

                val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (tokens.isEmpty()) continue

                val domains = if (looksLikeIp(tokens[0])) tokens.drop(1) else tokens.take(1)
                for (token in domains) {
                    val domain = token.trimEnd('.').lowercase()
                    if (domain.isEmpty() || domain in skip) continue
                    if (!domain.contains('.')) continue
                    into.add(domain)
                }
            }
        }
    }

    private fun looksLikeIp(token: String): Boolean =
        token.contains(':') || token.all { it.isDigit() || it == '.' }
}
