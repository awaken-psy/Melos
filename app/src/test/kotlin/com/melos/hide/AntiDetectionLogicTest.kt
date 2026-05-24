package com.melos.hide

import org.junit.Assert.*
import org.junit.Test

class AntiDetectionLogicTest {

    // ── isSuspiciousPath ────────────────────────────────────────────

    @Test
    fun `su binary is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/bin/su"))
    }

    @Test
    fun `su file ending is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/xbin/su"))
    }

    @Test
    fun `magisk path is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/data/adb/magisk"))
    }

    @Test
    fun `magisk in mixed case is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/data/adb/Magisk"))
    }

    @Test
    fun `lsposed path is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/data/adb/lspd"))
    }

    @Test
    fun `xposed path is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/framework/xposed.jar"))
    }

    @Test
    fun `busybox is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/xbin/busybox"))
    }

    @Test
    fun `supersu path is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/app/Superuser.apk"))
    }

    @Test
    fun `data adb root is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/data/adb"))
    }

    @Test
    fun `frida is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/data/local/tmp/frida"))
    }

    @Test
    fun `normal app data is not suspicious`() {
        assertFalse(AntiDetection.isSuspiciousPath("/data/data/com.tencent.mm/"))
    }

    @Test
    fun `normal system path is not suspicious`() {
        assertFalse(AntiDetection.isSuspiciousPath("/system/framework/framework.jar"))
    }

    @Test
    fun `normal sdcard path is not suspicious`() {
        assertFalse(AntiDetection.isSuspiciousPath("/sdcard/DCIM/photo.jpg"))
    }

    @Test
    fun `riru is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/data/adb/riru"))
    }

    @Test
    fun `edxposed is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/data/adb/edxposed"))
    }

    @Test
    fun `path ending with su is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/bin/su"))
    }

    @Test
    fun `suffix su is detected`() {
        // The check is `p.endsWith("/su")` which requires a path ending exactly in /su
        assertTrue(AntiDetection.isSuspiciousPath("/system/xbin/su"))
    }

    @Test
    fun `superuser in path is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/app/Superuser.apk"))
    }

    @Test
    fun `daemonsu is suspicious`() {
        assertTrue(AntiDetection.isSuspiciousPath("/system/bin/daemonsu"))
    }

    // ── isRootCommand ──────────────────────────────────────────────

    @Test
    fun `su command is root`() {
        assertTrue(AntiDetection.isRootCommand("su"))
    }

    @Test
    fun `full path su is root`() {
        assertTrue(AntiDetection.isRootCommand("/system/bin/su"))
    }

    @Test
    fun `which su is root`() {
        assertTrue(AntiDetection.isRootCommand("which su"))
    }

    @Test
    fun `busybox is root`() {
        assertTrue(AntiDetection.isRootCommand("busybox"))
    }

    @Test
    fun `magisk in command is root`() {
        assertTrue(AntiDetection.isRootCommand("magisk --install"))
    }

    @Test
    fun `su with space prefix is root`() {
        assertTrue(AntiDetection.isRootCommand("su -c id"))
    }

    @Test
    fun `su with trailing space is root`() {
        assertTrue(AntiDetection.isRootCommand("which su "))
    }

    @Test
    fun `ls is not root`() {
        assertFalse(AntiDetection.isRootCommand("ls /data"))
    }

    @Test
    fun `cat is not root`() {
        assertFalse(AntiDetection.isRootCommand("cat /proc/version"))
    }

    @Test
    fun `pm list is not root`() {
        assertFalse(AntiDetection.isRootCommand("pm list packages"))
    }

    @Test
    fun `uppercase SU is detected`() {
        // The check lowercases the command
        assertTrue(AntiDetection.isRootCommand("SU"))
    }

    @Test
    fun `mixed case magisk is detected`() {
        assertTrue(AntiDetection.isRootCommand("Magisk"))
    }

    @Test
    fun `empty string is not root`() {
        assertFalse(AntiDetection.isRootCommand(""))
    }

    @Test
    fun `substring su in word is detected with space check`() {
        // "sudo" contains "su" at start
        // c.startsWith("su ") matches "sudo ..." only if there's a space
        assertFalse(AntiDetection.isRootCommand("sudo apt install"))
    }

    // ── Blacklisted packages coverage ───────────────────────────────

    @Test
    fun `isSuspiciousPath on package name containing magisk returns true`() {
        // "com.topjohnwu.magisk" contains the token "magisk" → true
        assertTrue(AntiDetection.isSuspiciousPath("com.topjohnwu.magisk"))
    }

    @Test
    fun `isSuspiciousPath on clean package name returns false`() {
        assertFalse(AntiDetection.isSuspiciousPath("com.tencent.mm"))
    }

    // ── isSELinuxCommand ────────────────────────────────────────────

    @Test
    fun `getenforce is selinux check`() {
        assertTrue(AntiDetection.isSELinuxCommand("getenforce"))
    }

    @Test
    fun `sestatus is selinux check`() {
        assertTrue(AntiDetection.isSELinuxCommand("sestatus"))
    }

    @Test
    fun `cat selinux enforce is selinux check`() {
        assertTrue(AntiDetection.isSELinuxCommand("cat /sys/fs/selinux/enforce"))
    }

    @Test
    fun `cat selinux policyvers is selinux check`() {
        assertTrue(AntiDetection.isSELinuxCommand("cat /sys/fs/selinux/policyvers"))
    }

    @Test
    fun `proc self attr current is selinux check`() {
        assertTrue(AntiDetection.isSELinuxCommand("cat /proc/self/attr/current"))
    }

    @Test
    fun `mixed case getenforce is detected`() {
        assertTrue(AntiDetection.isSELinuxCommand("GetEnForce"))
    }

    @Test
    fun `getenforce with args is detected`() {
        assertTrue(AntiDetection.isSELinuxCommand("sh -c getenforce"))
    }

    @Test
    fun `ls is not selinux check`() {
        assertFalse(AntiDetection.isSELinuxCommand("ls /data"))
    }

    @Test
    fun `empty string is not selinux check`() {
        assertFalse(AntiDetection.isSELinuxCommand(""))
    }

    @Test
    fun `cat proc version is not selinux check`() {
        assertFalse(AntiDetection.isSELinuxCommand("cat /proc/version"))
    }
}
