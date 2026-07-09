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

    /**
     * Payment and anti-fraud services that many blocklists include (they do fingerprint
     * devices) but whose loss silently breaks card payments and checkouts — 3-D Secure
     * risk checks fail and the transaction is rejected even after the bank approves it
     * (ThreatMetrix, DataDome, iovation…). Allowed by default; a user can re-block one
     * by putting the exact domain in their custom blocked list.
     */
    internal val PAYMENT_ALLOWLIST = setOf(
        "online-metrix.net",     // ThreatMetrix / LexisNexis, used by most banks' 3DS
        "datadome.co",           // bot protection (SNCF Connect, many French sites)
        "iesnare.com",           // iovation
        "iovation.com",
        "forter.com",
        "riskified.com",
        "kount.com",
        "signifyd.com",
        "sift.com",
        "cardinalcommerce.com",  // Visa 3-D Secure
        "ravelin.com",
        "seon.io",
    )

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

        val customBlocked = prefs.customBlocked
        set.addAll(customBlocked)
        val allowSet = buildAllowSet(prefs.customAllowed, customBlocked)

        blocked = set
        allowed = allowSet
        domainCount = set.size
        isLoaded = true
        Log.i(TAG, "Loaded $source blocklist: ${set.size} domains, ${allowSet.size} allowed")
    }

    /**
     * The effective allowlist: the user's own entries plus the built-in payment
     * allowlist, minus anything the user explicitly re-blocked.
     */
    internal fun buildAllowSet(
        customAllowed: Collection<String>,
        customBlocked: Collection<String>,
    ): HashSet<String> {
        val allow = customAllowed.toHashSet()
        allow.addAll(PAYMENT_ALLOWLIST - customBlocked.toSet())
        return allow
    }

    fun ensureLoaded(context: Context) {
        if (!isLoaded) load(context)
    }

    // ------------------------------------------------------------------ blocked-domain log

    class BlockedEvent(val domain: String) {
        var count: Int = 1
        var lastSeen: Long = System.currentTimeMillis()
        /** UID of the app that sent the query, when the kernel could attribute it; -1 otherwise. */
        var uid: Int = -1
    }

    private const val BLOCK_LOG_CAPACITY = 100
    private val blockLog = object : LinkedHashMap<String, BlockedEvent>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, BlockedEvent>): Boolean =
            size > BLOCK_LOG_CAPACITY
    }

    /** Called from the packet path for every blocked query. Cheap: map upsert. */
    fun recordBlocked(domain: String, uid: Int = -1) {
        synchronized(blockLog) {
            val existing = blockLog[domain]
            if (existing != null) {
                existing.count++
                existing.lastSeen = System.currentTimeMillis()
                if (uid > 0) existing.uid = uid
            } else {
                blockLog[domain] = BlockedEvent(domain).also { if (uid > 0) it.uid = uid }
            }
        }
    }

    /** Most recently blocked domains first. */
    fun recentlyBlocked(): List<BlockedEvent> =
        synchronized(blockLog) { blockLog.values.sortedByDescending { it.lastSeen } }

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
            connection.setRequestProperty("User-Agent", "DNS67/1.0")
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
