package io.github.magisk317.xposed.logging

import android.content.pm.ApplicationInfo
import android.os.Process
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallerGuardTest {
    @Test
    fun `default privileged policy accepts only trusted uid classes`() {
        assertTrue(CallerGuard.isPrivilegedUid(Process.SYSTEM_UID, appUid = 12_345))
        assertTrue(CallerGuard.isPrivilegedUid(Process.PHONE_UID, appUid = 12_345))
        assertTrue(CallerGuard.isPrivilegedUid(uid = 12_345, appUid = 12_345))
        assertFalse(CallerGuard.isPrivilegedUid(uid = 20_000, appUid = 12_345))
        assertFalse(CallerGuard.isPrivilegedUid(uid = 20_000, appUid = null))
    }

    @Test
    fun `package policy requires an allowed system package`() {
        val guard = PackageCallerGuard(setOf("com.android.phone"))

        assertTrue(guard.isPackageAllowed("com.android.phone", ApplicationInfo.FLAG_SYSTEM))
        assertTrue(guard.isPackageAllowed("com.android.phone", ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
        assertFalse(guard.isPackageAllowed("com.android.phone", 0))
        assertFalse(guard.isPackageAllowed("com.example.attacker", ApplicationInfo.FLAG_SYSTEM))
    }

    @Test
    fun `package policy makes phone uid opt in`() {
        assertFalse(
            PackageCallerGuard.isPrivilegedUid(
                uid = Process.PHONE_UID,
                appUid = 12_345,
            ),
        )
        assertTrue(
            PackageCallerGuard.isPrivilegedUid(
                uid = Process.PHONE_UID,
                appUid = 12_345,
                allowPhoneUid = true,
            ),
        )
    }
}
