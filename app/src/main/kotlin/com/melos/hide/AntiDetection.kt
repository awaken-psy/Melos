package com.melos.hide

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.IOException

/**
 * Hides the root + LSPosed/Xposed environment from the target process.
 *
 * A course exercise tracker that detects a tampered device can reject the run
 * outright, which would defeat every other piece of spoofing in this module.
 * We therefore neutralise the *common, naive* detection vectors a mini-program
 * is likely to use:
 *
 *  - filesystem probes for su / Magisk / Xposed artefacts
 *  - shelling out to `su` / `which su` / busybox
 *  - querying PackageManager for Magisk / LSPosed manager packages
 *  - `Build.TAGS` / `Build.FINGERPRINT` carrying "test-keys"
 *  - `Class.forName("de.robv.android.xposed.…")` reflection checks
 *
 * Out of scope (deep detection — would need native-level work): scanning
 * /proc/self/maps for injected .so files and walking Throwable stack traces
 * for Xposed frames. These remain residual risks.
 */
object AntiDetection {

    private const val TAG = "Melos/Hide"

    /** Path fragments that betray root / Xposed when probed via File.exists(). */
    private val SUSPICIOUS_PATH_TOKENS = listOf(
        "magisk", "/.magisk", "busybox", "supersu", "superuser",
        "daemonsu", "xposed", "lsposed", "edxposed", "riru", "/data/adb", "frida",
    )

    /** Package names of root / Xposed managers to hide from PackageManager. */
    private val BLACKLISTED_PACKAGES = setOf(
        "com.topjohnwu.magisk",
        "eu.chainfire.supersu",
        "com.koushikdutta.superuser",
        "com.noshufou.android.su",
        "com.noshufou.android.su.elite",
        "com.thirdparty.superuser",
        "com.yellowes.su",
        "de.robv.android.xposed.installer",
        "org.lsposed.manager",
        "io.github.lsposed.manager",
        "org.meowcat.edxposed.manager",
        "com.solohsu.android.edxp.manager",
    )

    /** Class-name prefixes that, if resolvable, reveal an Xposed framework. */
    private val XPOSED_CLASS_TOKENS = listOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.lsposed",
        "com.saurik.substrate",
        "edxposed",
    )

    fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        try {
            hideRootFiles()
            hideRootExec()
            hidePackages(cl)
            sanitizeBuildProps()
            hideXposedClassLookup()
            XposedBridge.log("[$TAG] anti-detection hooks installed")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] install failed: ${e.message}")
        }
    }

    private fun isSuspiciousPath(path: String): Boolean {
        val p = path.lowercase()
        if (p.endsWith("/su")) return true
        return SUSPICIOUS_PATH_TOKENS.any { p.contains(it) }
    }

    private fun isRootCommand(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c == "su" || c.endsWith("/su") ||
            c.contains("which su") || c.contains("busybox") ||
            c.contains("magisk") || c.contains(" su ") || c.startsWith("su ")
    }

    /** java.io.File.exists() → false for known root/Xposed artefacts. */
    private fun hideRootFiles() {
        XposedHelpers.findAndHookMethod(
            File::class.java, "exists",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val f = param.thisObject as? File ?: return
                    if (isSuspiciousPath(f.absolutePath)) {
                        param.result = false
                    }
                }
            }
        )
    }

    /**
     * Runtime.exec(...) → throw IOException for root-probing commands, exactly
     * as a clean device would when `su` is not on the PATH.
     */
    private fun hideRootExec() {
        val handler = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cmd = when (val arg = param.args[0]) {
                    is String -> arg
                    is Array<*> -> arg.filterIsInstance<String>().joinToString(" ")
                    else -> return
                }
                if (isRootCommand(cmd)) {
                    param.throwable = IOException("Cannot run program \"su\": error=2, No such file or directory")
                }
            }
        }
        XposedHelpers.findAndHookMethod(Runtime::class.java, "exec", String::class.java, handler)
        XposedHelpers.findAndHookMethod(Runtime::class.java, "exec", Array<String>::class.java, handler)
    }

    /** Hide root/Xposed packages from PackageManager queries. */
    private fun hidePackages(cl: ClassLoader) {
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl)

        // getPackageInfo(String, int) / getApplicationInfo(String, int) → NameNotFound
        val notFound = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val pkg = param.args[0] as? String ?: return
                if (pkg in BLACKLISTED_PACKAGES) {
                    param.throwable = android.content.pm.PackageManager.NameNotFoundException(pkg)
                }
            }
        }
        XposedHelpers.findAndHookMethod(
            pmClass, "getPackageInfo", String::class.java, Int::class.javaPrimitiveType, notFound
        )
        XposedHelpers.findAndHookMethod(
            pmClass, "getApplicationInfo", String::class.java, Int::class.javaPrimitiveType, notFound
        )

        // getInstalledPackages(int) / getInstalledApplications(int) → filter the list
        val filterList = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.result as? List<*> ?: return
                val cleaned = list.filter { item ->
                    val pn = try {
                        XposedHelpers.getObjectField(item, "packageName") as? String
                    } catch (e: Throwable) {
                        null
                    }
                    pn == null || pn !in BLACKLISTED_PACKAGES
                }
                if (cleaned.size != list.size) {
                    param.result = ArrayList(cleaned)
                }
            }
        }
        XposedHelpers.findAndHookMethod(
            pmClass, "getInstalledPackages", Int::class.javaPrimitiveType, filterList
        )
        XposedHelpers.findAndHookMethod(
            pmClass, "getInstalledApplications", Int::class.javaPrimitiveType, filterList
        )
    }

    /** Replace test-keys signals in Build with stock release values. */
    private fun sanitizeBuildProps() {
        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) {
            XposedHelpers.setStaticObjectField(Build::class.java, "TAGS", "release-keys")
        }
        val fingerprint = Build.FINGERPRINT
        if (fingerprint != null && (fingerprint.contains("test-keys") || fingerprint.contains("userdebug"))) {
            XposedHelpers.setStaticObjectField(
                Build::class.java, "FINGERPRINT",
                fingerprint.replace("test-keys", "release-keys").replace("userdebug", "user")
            )
        }
    }

    /** Class.forName("de.robv.android.xposed.…") → ClassNotFoundException. */
    private fun hideXposedClassLookup() {
        val handler = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = (param.args[0] as? String)?.lowercase() ?: return
                if (XPOSED_CLASS_TOKENS.any { name.contains(it) }) {
                    param.throwable = ClassNotFoundException(param.args[0] as String)
                }
            }
        }
        XposedHelpers.findAndHookMethod(Class::class.java, "forName", String::class.java, handler)
        XposedHelpers.findAndHookMethod(
            Class::class.java, "forName",
            String::class.java, Boolean::class.javaPrimitiveType, ClassLoader::class.java,
            handler
        )
    }
}
