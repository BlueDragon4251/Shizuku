package moe.shizuku.manager.management

import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.MenuItem
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AppsActivityBinding
import moe.shizuku.manager.utils.CustomTabsHelper
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku
import java.util.*

class ApplicationManagementActivity : AppBarActivity() {

    private val viewModel by appsViewModel()
    private val adapter = AppsAdapter()
    private var hasResumed = false

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w("ShizukuDiscovery", "ui binder died; closing application management")
        if (!isFinishing) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binderAlive = Shizuku.pingBinder()
        Log.i("ShizukuDiscovery", "ui create binderAlive=$binderAlive restored=${savedInstanceState != null} cachedStatus=${viewModel.packages.value?.status}")
        if (!binderAlive) {
            finish()
            return
        }

        val binding = AppsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        viewModel.packages.observe(this) {
            Log.i("ShizukuDiscovery", "ui result status=${it.status} packages=${it.data?.size}")
            when (it.status) {
                Status.SUCCESS -> {
                    adapter.updateData(it.data)
                }
                Status.ERROR -> {
                    finish()
                    val tr = it.error
                    Log.e("ShizukuDiscovery", "ui application list failed", tr)
                    Toast.makeText(this, Objects.toString(tr, "unknown"), Toast.LENGTH_SHORT).show()
                    tr.printStackTrace()
                }
                Status.LOADING -> {

                }
            }
        }
        if (viewModel.packages.value == null) {
            viewModel.load()
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                viewModel.load(true)
            }
        })

        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    override fun onDestroy() {
        super.onDestroy()

        Shizuku.removeBinderDeadListener(binderDeadListener)
    }

    override fun onResume() {
        super.onResume()
        Log.i("ShizukuDiscovery", "ui resume refresh=$hasResumed cachedStatus=${viewModel.packages.value?.status} adapterItems=${adapter.itemCount}")
        adapter.notifyDataSetChanged()
        if (hasResumed) {
            // Requery on return so an earlier empty list cannot remain cached forever.
            viewModel.load()
        }
        hasResumed = true
    }
}
