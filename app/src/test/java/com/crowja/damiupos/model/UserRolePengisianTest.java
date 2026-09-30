package com.crowja.damiupos.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Predikat peran "pengisian" (Karyawan Pengisian Day-time) — satu sumber kebenaran untuk absensi,
 * peredaman notifikasi operasional, dan akses layar. Menjaga supaya peran lama TIDAK bergeser.
 */
public class UserRolePengisianTest {

    private static User as(String role) {
        User u = new User();
        u.setRole(role);
        return u;
    }

    @Test public void roleStringMatchesServerRole() {
        assertEquals("pengisian", User.ROLE_PENGISIAN);
        assertTrue(as("pengisian").isPengisian());
        assertFalse(as("staf").isPengisian());
        assertFalse(as("PENGISIAN ").isPengisian());   // peran server selalu huruf kecil persis
    }

    @Test public void pengisianClocksInLikeStaf() {
        assertTrue(as(User.ROLE_PENGISIAN).tracksAttendance());
    }

    @Test public void attendanceRolesUnchanged() {
        assertTrue(as(User.ROLE_STAF).tracksAttendance());
        assertTrue(as(User.ROLE_SPV).tracksAttendance());
        assertTrue(as(User.ROLE_MARKETING).tracksAttendance());
        assertFalse(as(User.ROLE_ADMIN).tracksAttendance());
        assertFalse(as(User.ROLE_VIEWER).tracksAttendance());
    }

    @Test public void operationalAlertsSilencedForMarketingAndPengisianOnly() {
        assertFalse(as(User.ROLE_MARKETING).receivesOperationalAlerts());
        assertFalse(as(User.ROLE_PENGISIAN).receivesOperationalAlerts());
        assertTrue(as(User.ROLE_ADMIN).receivesOperationalAlerts());
        assertTrue(as(User.ROLE_STAF).receivesOperationalAlerts());
        assertTrue(as(User.ROLE_SPV).receivesOperationalAlerts());
        assertTrue(as(User.ROLE_VIEWER).receivesOperationalAlerts());
    }

    @Test public void orderChatAlertsSilencedForPengisianOnly() {
        // Chat Pesanan: marketing memakainya (TIDAK boleh ikut diredam); Pengisian tak melayani pelanggan.
        assertFalse(as(User.ROLE_PENGISIAN).receivesOrderChatAlerts());
        assertTrue(as(User.ROLE_MARKETING).receivesOrderChatAlerts());
        assertTrue(as(User.ROLE_ADMIN).receivesOrderChatAlerts());
        assertTrue(as(User.ROLE_STAF).receivesOrderChatAlerts());
        assertTrue(as(User.ROLE_SPV).receivesOrderChatAlerts());
        assertTrue(as(User.ROLE_VIEWER).receivesOrderChatAlerts());
    }

    @Test public void pengisianCannotTransactOrTouchOtherScreens() {
        User u = as(User.ROLE_PENGISIAN);
        assertFalse(u.canCreateTransaction());
        assertFalse(u.canDeleteTransaction());
        assertFalse(u.canDeleteCustomer());
        assertFalse(u.canManageReseller());
        assertFalse(u.canGiveFree());
        assertFalse(u.canEditTransactionLimited());
        assertFalse(u.canViewSalesAchievement());
        assertFalse(u.canViewDeliveryRecord());
        assertFalse(u.canUseWaChat());
    }

    @Test public void pengisianScreenAccessAndHomeLock() {
        assertTrue(as(User.ROLE_PENGISIAN).canOpenPengisianScreen());
        assertTrue(as(User.ROLE_ADMIN).canOpenPengisianScreen());
        assertFalse(as(User.ROLE_STAF).canOpenPengisianScreen());
        assertFalse(as(User.ROLE_MARKETING).canOpenPengisianScreen());
        assertFalse(as(User.ROLE_VIEWER).canOpenPengisianScreen());
        assertFalse(as("tak-dikenal").canOpenPengisianScreen());

        // hanya Pengisian yang DIKUNCI ke layar itu; admin boleh membukanya tapi tak dialihkan
        assertTrue(as(User.ROLE_PENGISIAN).usesPengisianHome());
        assertFalse(as(User.ROLE_ADMIN).usesPengisianHome());
    }

    @Test public void unknownRoleStillDegradesSafely() {
        User u = as("peran-baru-di-apk-lama");
        assertFalse(u.tracksAttendance());
        assertFalse(u.canCreateTransaction());
        assertFalse(u.isPengisian());
        assertTrue(u.receivesOperationalAlerts());
    }
}
