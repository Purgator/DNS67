package fr.arichard.adblocker

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.arichard.adblocker.core.AppNames
import fr.arichard.adblocker.core.BlocklistManager
import fr.arichard.adblocker.core.DebugNotifier
import fr.arichard.adblocker.core.Prefs
import fr.arichard.adblocker.databinding.ActivityRecentlyBlockedBinding
import fr.arichard.adblocker.databinding.ItemBlockedDomainBinding
import kotlin.concurrent.thread

/**
 * Live view of the most recently blocked domains, with app attribution where the
 * kernel can provide it. Tapping a row allowlists the domain after confirmation.
 */
class RecentlyBlockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecentlyBlockedBinding
    private lateinit var prefs: Prefs
    private val adapter = BlockedAdapter { confirmAllowDomain(it) }
    private val handler = Handler(Looper.getMainLooper())

    private val refreshLoop = object : Runnable {
        override fun run() {
            refreshList()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecentlyBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)

        binding.blockedList.layoutManager = LinearLayoutManager(this)
        binding.blockedList.adapter = adapter

        binding.debugSwitch.isChecked = prefs.debugNotifications
        binding.debugSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.debugNotifications = checked
            if (!checked) DebugNotifier.clear(this)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        binding.debugSwitch.isChecked = prefs.debugNotifications
        handler.post(refreshLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshLoop)
    }

    private fun refreshList() {
        val events = BlocklistManager.recentlyBlocked()
        binding.emptyText.isVisible = events.isEmpty()
        adapter.submit(events)
    }

    private fun confirmAllowDomain(domain: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.allow_domain_title, domain))
            .setMessage(R.string.allow_domain_message)
            .setPositiveButton(R.string.allow) { _, _ ->
                prefs.appendCustomAllowed(domain)
                thread { BlocklistManager.load(applicationContext) }
                Toast.makeText(
                    this, getString(R.string.domain_allowed_toast, domain), Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class BlockedAdapter(
        private val onTap: (String) -> Unit,
    ) : RecyclerView.Adapter<BlockedAdapter.Holder>() {

        private var events: List<BlocklistManager.BlockedEvent> = emptyList()
        private val iconCache = HashMap<Int, Drawable?>()

        fun submit(list: List<BlocklistManager.BlockedEvent>) {
            events = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = events.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemBlockedDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(events[position])

        inner class Holder(private val item: ItemBlockedDomainBinding) :
            RecyclerView.ViewHolder(item.root) {

            fun bind(event: BlocklistManager.BlockedEvent) {
                item.domainText.text = event.domain
                val time = DateUtils.getRelativeTimeSpanString(
                    event.lastSeen, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS
                )
                val app = AppNames.label(this@RecentlyBlockedActivity, event.uid)
                item.detailText.text = listOfNotNull(app, "×${event.count}", time)
                    .joinToString(" · ")

                val icon = iconCache.getOrPut(event.uid) {
                    AppNames.icon(this@RecentlyBlockedActivity, event.uid)
                }
                if (icon != null) {
                    item.appIcon.setImageDrawable(icon)
                    item.appIcon.clearColorFilter()
                    item.appIcon.alpha = 1f
                } else {
                    item.appIcon.setImageResource(R.drawable.ic_shield)
                    item.appIcon.alpha = 0.35f
                }
                item.root.setOnClickListener { onTap(event.domain) }
            }
        }
    }

    private companion object {
        const val REFRESH_MS = 2000L
    }
}
