package moe.shizuku.common.util;

import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.IUserManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

import rikka.hidden.compat.util.SystemServiceBinder;

/** Platform app/user enumeration that retains failures instead of reporting an empty result. */
public final class DiscoveryCompat {

    public static final String TAG = "ShizukuDiscovery";

    // SystemServiceBinder also applies the manager's ShizukuBinderWrapper listener.
    private static final SystemServiceBinder<IPackageManager> PACKAGE_MANAGER =
            new SystemServiceBinder<>("package", IPackageManager.Stub::asInterface);
    private static final SystemServiceBinder<IUserManager> USER_MANAGER =
            new SystemServiceBinder<>("user", IUserManager.Stub::asInterface);

    private DiscoveryCompat() {
    }

    @SuppressWarnings("unchecked")
    public static List<PackageInfo> getInstalledPackages(long flags, int userId) throws Throwable {
        try {
            IPackageManager packageManager = PACKAGE_MANAGER.get();
            if (packageManager == null) {
                throw new IllegalStateException("Package manager service is unavailable");
            }
            Method method = PlatformApiReflection.installedPackagesMethod(
                    IPackageManager.class, Build.VERSION.SDK_INT >= 33);
            Log.i(TAG, "getInstalledPackages user=" + userId + " flags=0x"
                    + Long.toHexString(flags) + " method=" + method.toGenericString());

            Object value = PlatformApiReflection.installedPackages(method, packageManager, flags, userId);
            // Android 17's PackageInfoList extends ParceledListSlice. Do not compile against
            // the new return class: the actual interface method is resolved on the device.
            if (!(value instanceof ParceledListSlice<?>)) {
                throw new IllegalStateException("getInstalledPackages returned "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            List<PackageInfo> packages = ((ParceledListSlice<PackageInfo>) value).getList();
            if (packages == null) {
                throw new IllegalStateException("getInstalledPackages returned a null package list");
            }
            Log.i(TAG, "getInstalledPackages user=" + userId + " count=" + packages.size());
            return packages;
        } catch (Throwable failure) {
            Log.e(TAG, "getInstalledPackages failed user=" + userId + " flags=0x"
                    + Long.toHexString(flags), failure);
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<UserInfo> getUsers(boolean excludeDying) throws Throwable {
        try {
            IUserManager userManager = USER_MANAGER.get();
            if (userManager == null) {
                throw new IllegalStateException("User manager service is unavailable");
            }
            Method method = PlatformApiReflection.usersMethod(IUserManager.class);
            Log.i(TAG, "getUsers excludeDying=" + excludeDying + " method=" + method.toGenericString());
            Object value = PlatformApiReflection.users(method, userManager, excludeDying);
            if (!(value instanceof List<?>)) {
                throw new IllegalStateException("getUsers returned "
                        + (value == null ? "null" : value.getClass().getName()));
            }
            List<UserInfo> users = (List<UserInfo>) value;
            Log.i(TAG, "getUsers count=" + users.size());
            return users;
        } catch (Throwable failure) {
            Log.e(TAG, "getUsers failed excludeDying=" + excludeDying, failure);
            throw failure;
        }
    }
}
