package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.shizuku.server.ServerConstants
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

object AuthorizationManager {

    private const val DISCOVERY_TAG = "ShizukuDiscovery"
    private val querySequence = AtomicInteger()

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int, queryId: Int): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val started = SystemClock.elapsedRealtime()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)
            val binder = checkNotNull(Shizuku.getBinder()) { "Shizuku binder is unavailable" }
            val handled = binder.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            Log.i(DISCOVERY_TAG, "manager query=$queryId transact user=$userId handled=$handled replyBytes=${reply.dataSize()} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            if (!handled) {
                throw IllegalStateException("Shizuku server did not handle getApplications")
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            val slice = ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>
            val packages = checkNotNull(slice.list) { "Shizuku server returned a null application list" }
            Log.i(DISCOVERY_TAG, "manager query=$queryId decoded packages=${packages.size} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            packages
        } catch (tr: Throwable) {
            Log.e(DISCOVERY_TAG, "manager query=$queryId getApplications failed user=$userId replyBytes=${reply.dataSize()}", tr)
            throw tr
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(): List<PackageInfo> {
        val queryId = querySequence.incrementAndGet()
        val started = SystemClock.elapsedRealtime()
        Log.i(DISCOVERY_TAG, "manager query=$queryId start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} sdk=${Build.VERSION.SDK_INT} uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} binderAlive=${Shizuku.pingBinder()}")
        try {
            val preV11 = Shizuku.isPreV11()
            val serverVersion = Shizuku.getVersion()
            val serverPatch = if (preV11) -1 else Shizuku.getServerPatchVersion()
            val legacy = preV11 || (serverVersion == 11 && serverPatch < 3)
            Log.i(DISCOVERY_TAG, "manager query=$queryId serverApi=$serverVersion patch=$serverPatch uid=${Shizuku.getUid()} path=${if (legacy) "legacy" else "server"}")
            val packages: MutableList<PackageInfo> = ArrayList()
            if (legacy) {
                val allPackages: MutableList<PackageInfo> = ArrayList()
                val users = ShizukuSystemApis.getUsers(useCache = false)
                Log.i(DISCOVERY_TAG, "manager query=$queryId legacy users=${users.map { it.id }}")
                var successfulUsers = 0
                var firstFailure: Throwable? = null
                for (user in users) {
                    try {
                        val installed = ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id)
                        Log.i(DISCOVERY_TAG, "manager query=$queryId legacy user=${user.id} scanned=${installed.size}")
                        allPackages.addAll(installed)
                        successfulUsers++
                    } catch (e: Throwable) {
                        if (firstFailure == null) firstFailure = e
                        Log.e(DISCOVERY_TAG, "manager query=$queryId legacy package query failed user=${user.id}", e)
                        LOGGER.w(e, "getInstalledPackages")
                    }
                }
                if (successfulUsers == 0 && firstFailure != null) {
                    throw IllegalStateException("Package discovery failed for all requested users", firstFailure)
                }
                for (pi in allPackages) {
                    if (BuildConfig.APPLICATION_ID == pi.packageName) continue
                    if (pi.requestedPermissions?.contains(Manifest.permission.API_V23) != true) continue
                    if (pi.applicationInfo == null) {
                        Log.w(DISCOVERY_TAG, "manager query=$queryId legacy filtered package=${pi.packageName} reason=missing_ApplicationInfo")
                        continue
                    }

                    // Discovery is based on the declared API permission, including older clients.
                    // This does not grant access or change server authorization checks.
                    packages.add(pi)
                }
            } else {
                packages.addAll(getApplications(-1, queryId))
            }
            for (pi in packages) {
                Log.i(DISCOVERY_TAG, "manager query=$queryId candidate package=${pi.packageName} uid=${pi.applicationInfo?.uid} requestsApiV23=${pi.requestedPermissions?.contains(Manifest.permission.API_V23) == true}")
            }
            Log.i(DISCOVERY_TAG, "manager query=$queryId complete packages=${packages.size} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            return packages
        } catch (tr: Throwable) {
            Log.e(DISCOVERY_TAG, "manager query=$queryId failed elapsedMs=${SystemClock.elapsedRealtime() - started}", tr)
            throw tr
        }
    }

    fun granted(packageName: String, uid: Int): Boolean {
        return if (Shizuku.isPreV11()) {
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
        } else {
            (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
        }
    }

    fun grant(packageName: String, uid: Int) {
        Log.i(DISCOVERY_TAG, "manager grant requested package=$packageName uid=$uid")
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
            }
            Log.i(DISCOVERY_TAG, "manager grant completed package=$packageName uid=$uid")
        } catch (tr: Throwable) {
            Log.e(DISCOVERY_TAG, "manager grant failed package=$packageName uid=$uid", tr)
            throw tr
        }
    }

    fun revoke(packageName: String, uid: Int) {
        Log.i(DISCOVERY_TAG, "manager revoke requested package=$packageName uid=$uid")
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
            }
            Log.i(DISCOVERY_TAG, "manager revoke completed package=$packageName uid=$uid")
        } catch (tr: Throwable) {
            Log.e(DISCOVERY_TAG, "manager revoke failed package=$packageName uid=$uid", tr)
            throw tr
        }
    }
}
