package com.melos.hide

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Hides the root + LSPosed/Xposed environment from the target process.
 *
 * Neutralises both naive and moderately deep detection vectors:
 *
 *  Layer 1 – filesystem / shell / package / build (initial release)
 *  Layer 2 – stack-trace sanitisation, system-property spoofing,
 *            developer/ADB settings hiding, ProcessBuilder blocking
 *  Layer 2+ – SELinux/verified-boot file+command blocking, full boot
 *            property spoofing
 *
 * Remaining residual risks (native-level, out of scope):
 *  - direct `openat("/proc/self/maps")` via JNI (can't hook from Java)
 *  - scanning linker internals from native code
 */
object AntiDetection {

    private const val TAG = "Melos/Hide"

    // ── Layer 1 constants ──────────────────────────────────────────────

    private val SUSPICIOUS_PATH_TOKENS = listOf(
        "magisk", "/.magisk", "busybox", "supersu", "superuser",
        "daemonsu", "xposed", "lsposed", "edxposed", "riru", "/data/adb", "frida",
    )

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

    private val XPOSED_CLASS_TOKENS = listOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.lsposed",
        "com.saurik.substrate",
        "edxposed",
    )

    // ── Layer 2 constants ──────────────────────────────────────────────

    /** Stack-trace class-name prefixes that reveal Xposed injection. */
    private val XPOSED_STACK_PREFIXES = arrayOf(
        "de.robv.android.xposed.",
        "org.lsposed.",
        "io.github.lsposed.",
        "com.saurik.substrate.",
        "edxposed.",
        "com.swift.sandhook.",
        "com.android.internal.XposedCompat.",
    )

    /** System properties that indicate a non-production build / debug state. */
    private val SENSITIVE_PROPS = mapOf(
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.build.selinux" to "1",
        "ro.adb.secure" to "1",
        "persist.sys.usb.config" to "none",
        "ro.build.type" to "user",
        "ro.boot.verifiedbootstate" to "green",
        "ro.boot.vbmeta.device_state" to "locked",
        "ro.bootloader.verifiedbootstate" to "green",
        "ro.bootloader.vbmeta.device_state" to "locked",
        "ro.boot.flash.locked" to "1",
        "ro.boot.veritymode" to "enforcing",
        "ro.boot.warranty_bit" to "0",
        "ro.warranty_bit" to "0",
        "sys.oem_unlock_allowed" to "0",
    )

    private val BLOCKED_FILE_PATHS = setOf(
        "/sys/fs/selinux/enforce",
        "/sys/fs/selinux/policyvers",
        "/proc/self/attr/current",
        "/proc/self/attr/prev",
        "/proc/self/attr/exec",
        "/proc/self/attr/fscreate",
    )

    // ── Public entry point ─────────────────────────────────────────────

    fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        try {
            // Layer 1
            hideRootFiles()
            hideRootExec()
            hidePackages(cl)
            sanitizeBuildProps()
            hideXposedClassLookup()

            // Layer 2
            hideStackTraces()
            hideSystemProperties()
            hideSettingsSecure(cl)
            hideProcessBuilder()

            // Layer 2+
            hideSELinux()

            XposedBridge.log("[$TAG] anti-detection hooks (layer 1+2+) installed")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] install failed: ${e.message}")
        }
    }

    // ── Layer 1 implementations ────────────────────────────────────────

    internal fun isSuspiciousPath(path: String): Boolean {
        val p = path.lowercase()
        if (p.endsWith("/su")) return true
        return SUSPICIOUS_PATH_TOKENS.any { p.contains(it) }
    }

    internal fun isRootCommand(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c == "su" || c.endsWith("/su") ||
            c.contains("which su") || c.contains("busybox") ||
            c.contains("magisk") || c.contains(" su ") || c.startsWith("su ")
    }

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

    private fun hideRootExec() {
        val handler = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val cmd = when (val arg = param.args[0]) {
                    is String -> arg
                    is Array<*> -> arg.filterIsInstance<String>().joinToString(" ")
                    else -> return
                }
                if (isRootCommand(cmd) || isSELinuxCommand(cmd)) {
                    param.throwable = IOException("Cannot run program \"su\": error=2, No such file or directory")
                }
            }
        }
        XposedHelpers.findAndHookMethod(Runtime::class.java, "exec", String::class.java, handler)
        XposedHelpers.findAndHookMethod(Runtime::class.java, "exec", Array<String>::class.java, handler)
    }

    private fun hidePackages(cl: ClassLoader) {
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl)

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

        val filterList = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.result as? List<*> ?: return
                val cleaned = list.filter { item ->
                    val pn = try {
                        XposedHelpers.getObjectField(item, "packageName") as? String
                    } catch (e: Throwable) { null }
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

    // ── Layer 2 implementations ────────────────────────────────────────

    /**
     * Sanitise stack traces from `Throwable.getStackTrace()` and
     * `Thread.getStackTrace()` so that Xposed hook-dispatch frames are
     * invisible.  Detection libraries commonly instantiate an Exception
     * and inspect the call stack for Xposed class names.
     */
    private fun hideStackTraces() {
        val filter = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val trace = param.result as? Array<*> ?: return
                val cleaned = trace.filterNotNull().filter { frame ->
                    val cls = (frame as? java.lang.StackTraceElement)?.className ?: return@filter true
                    XPOSED_STACK_PREFIXES.none { cls.startsWith(it) }
                }
                if (cleaned.size != trace.size) {
                    @Suppress("UNCHECKED_CAST")
                    param.result = cleaned.toTypedArray()
                }
            }
        }
        XposedHelpers.findAndHookMethod(Throwable::class.java, "getStackTrace", filter)
        XposedHelpers.findAndHookMethod(Thread::class.java, "getStackTrace", filter)
    }

    /**
     * Intercept `android.os.SystemProperties.get(String)` to spoof sensitive
     * properties that reveal debuggable / engineering builds.
     */
    private fun hideSystemProperties() {
        runCatching {
            val spClass = XposedHelpers.findClass("android.os.SystemProperties", null)
            XposedHelpers.findAndHookMethod(
                spClass, "get", String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        SENSITIVE_PROPS[key]?.let { param.result = it }
                    }
                }
            )
        }.onFailure {
            // SystemProperties might not be directly hookable on all ROMs
            XposedBridge.log("[$TAG] SystemProperties hook skipped: ${it.message}")
        }
    }

    /**
     * Hide developer / ADB indicators from Settings.Secure and Settings.Global.
     * A non-rooted, production device should report adb_enabled=0 and
     * development_settings_enabled=0.
     */
    private fun hideSettingsSecure(cl: ClassLoader) {
        val sensitiveSettings = setOf(
            "adb_enabled",
            "development_settings_enabled",
            "adb_wifi_enabled",
            "always_finish_activities",
        )

        val overrideSettings = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val name = param.args[0] as? String ?: return
                if (name in sensitiveSettings) {
                    param.result = "0"
                }
            }
        }
        val overrideInt = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val name = param.args[0] as? String ?: return
                if (name in sensitiveSettings) {
                    param.result = 0
                }
            }
        }

        // Settings.Secure
        runCatching {
            val secureClass = XposedHelpers.findClass("android.provider.Settings\$Secure", cl)
            XposedHelpers.findAndHookMethod(secureClass, "getString",
                android.content.ContentResolver::class.java, String::class.java, overrideSettings)
            XposedHelpers.findAndHookMethod(secureClass, "getInt",
                android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, overrideInt)
        }

        // Settings.Global
        runCatching {
            val globalClass = XposedHelpers.findClass("android.provider.Settings\$Global", cl)
            XposedHelpers.findAndHookMethod(globalClass, "getString",
                android.content.ContentResolver::class.java, String::class.java, overrideSettings)
            XposedHelpers.findAndHookMethod(globalClass, "getInt",
                android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType, overrideInt)
        }
    }

    /**
     * Hook `ProcessBuilder.start()` — another vector for shell commands that
     * bypasses `Runtime.exec()`.  Apply the same root-command heuristics.
     */
    private fun hideProcessBuilder() {
        XposedHelpers.findAndHookMethod(
            ProcessBuilder::class.java, "start",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pb = param.thisObject as? ProcessBuilder ?: return
                    val cmd = pb.command().joinToString(" ")
                    if (isRootCommand(cmd) || isSELinuxCommand(cmd)) {
                        param.throwable = IOException("Cannot run program \"su\": error=2, No such file or directory")
                    }
                }
            }
        )
    }

    internal fun isSELinuxCommand(cmd: String): Boolean {
        val c = cmd.lowercase()
        return c.contains("getenforce") ||
            c.contains("sestatus") ||
            c.contains("selinux/enforce") ||
            c.contains("selinux/policyvers") ||
            c.contains("/proc/self/attr/")
    }

    private fun hideSELinux() {
        val blockFileRead = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val path = when (val arg = param.args.firstOrNull()) {
                    is String -> arg
                    is File -> arg.absolutePath
                    else -> return
                }
                if (path in BLOCKED_FILE_PATHS) {
                    param.throwable = IOException("open: Permission denied")
                }
            }
        }
        runCatching {
            XposedHelpers.findAndHookConstructor(FileInputStream::class.java, String::class.java, blockFileRead)
        }
        runCatching {
            XposedHelpers.findAndHookConstructor(FileInputStream::class.java, File::class.java, blockFileRead)
        }

        XposedHelpers.findAndHookMethod(
            File::class.java, "canRead",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val file = param.thisObject as? File ?: return
                    if (file.absolutePath in BLOCKED_FILE_PATHS) {
                        param.result = false
                    }
                }
            }
        )

        XposedBridge.log("[$TAG] SELinux/verified-boot hiding hooks installed")
    }
}
