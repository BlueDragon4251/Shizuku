package moe.shizuku.manager.management

import android.content.Context
import android.content.pm.PackageInfo
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.authorization.AuthorizationManager
import rikka.lifecycle.Resource
import rikka.lifecycle.activityViewModels
import rikka.lifecycle.viewModels
import java.util.concurrent.atomic.AtomicInteger

@MainThread
fun ComponentActivity.appsViewModel() = viewModels { AppsViewModel(this) }

@MainThread
fun Fragment.appsViewModel() = activityViewModels { AppsViewModel(requireContext()) }

class AppsViewModel(context: Context) : ViewModel() {

    private val loadSequence = AtomicInteger()

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>()
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>()
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    fun load(onlyCount: Boolean = false) {
        val loadId = loadSequence.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            var stage = "query"
            var currentPackage: String? = null
            Log.i("ShizukuDiscovery", "viewModel load=$loadId start onlyCount=$onlyCount")
            try {
                val list: MutableList<PackageInfo> = ArrayList()
                var count = 0
                for (pi in AuthorizationManager.getPackages()) {
                    currentPackage = pi.packageName
                    list.add(pi)
                    stage = "authorization"
                    val uid = checkNotNull(pi.applicationInfo) { "ApplicationInfo missing for ${pi.packageName}" }.uid
                    val granted = AuthorizationManager.granted(pi.packageName, uid)
                    Log.i("ShizukuDiscovery", "viewModel load=$loadId client package=${pi.packageName} uid=$uid granted=$granted")
                    if (granted) count++
                }
                stage = "publish"
                if (!onlyCount) _packages.postValue(Resource.success(list))
                _grantedCount.postValue(Resource.success(count))
                Log.i("ShizukuDiscovery", "viewModel load=$loadId complete packages=${list.size} granted=$count onlyCount=$onlyCount elapsedMs=${SystemClock.elapsedRealtime() - started}")
            } catch (e: CancellationException) {
                Log.i("ShizukuDiscovery", "viewModel load=$loadId cancelled stage=$stage")
                throw e
            } catch (e: Throwable) {
                Log.e("ShizukuDiscovery", "viewModel load=$loadId failed stage=$stage package=$currentPackage onlyCount=$onlyCount", e)
                _packages.postValue(Resource.error(e, null))
                _grantedCount.postValue(Resource.error(e, 0))
            }
        }
    }
}
