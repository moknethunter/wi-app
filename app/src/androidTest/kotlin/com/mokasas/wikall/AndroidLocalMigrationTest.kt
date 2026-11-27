package com.mokasas.wikall

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLocalMigrationTest {
    @Test fun migratesLegacyRosterProfileAndMissedItemsWithoutLosingData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "wikall-migration-${UUID.randomUUID()}.db"
        val realm = "legacy-realm"
        val account = "$realm:99001"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                old.execSQL("CREATE TABLE AssignedDevice (number TEXT NOT NULL PRIMARY KEY, serial TEXT NOT NULL, signing TEXT NOT NULL, encryption TEXT NOT NULL, activationId TEXT NOT NULL, revoked INTEGER NOT NULL)")
                old.execSQL("CREATE UNIQUE INDEX index_AssignedDevice_serial ON AssignedDevice(serial)")
                old.execSQL("CREATE TABLE ConnectionProfile (slot INTEGER NOT NULL PRIMARY KEY, address TEXT NOT NULL, pin TEXT NOT NULL, credential TEXT NOT NULL)")
                old.execSQL("CREATE TABLE CallHistory (id TEXT NOT NULL PRIMARY KEY, peer TEXT NOT NULL, state TEXT NOT NULL, endedAt INTEGER NOT NULL)")
                old.execSQL("CREATE TABLE IncomingMissed (account TEXT NOT NULL, id TEXT NOT NULL, peer TEXT NOT NULL, endedAt INTEGER NOT NULL, PRIMARY KEY(account, id))")
                old.execSQL("INSERT INTO AssignedDevice VALUES ('99001','serial','signing','encryption','activation',0)")
                old.execSQL("INSERT INTO ConnectionProfile VALUES (0,'192.168.1.2','','opaque-legacy-profile')")
                old.execSQL("INSERT INTO IncomingMissed VALUES (?, 'legacy-item', '99000', 1)", arrayOf(account))
                old.version = 3
            }
            val migrated = openLocalDatabase(context, name)
            try {
                val data = migrated.data()
                val legacy = checkNotNull(data.device("99001"))
                assertNull(legacy.expiresAt)
                assertFalse(legacy.activated)
                assertEquals("opaque-legacy-profile", data.profile()?.credential)
                assertEquals("legacy-item", data.missed(account).single().id)
                data.markRead(account, "legacy-item")
                assertTrue(data.missed(account).isEmpty())
                data.enqueueMissed(LocalMissedDelivery(realm, "99001", "delivered-item", "99000", 2))
                assertEquals(1, data.pendingMissed(realm, "99001").size)
                data.ackMissed(realm, "99001", "delivered-item")
                val packet = ChatPacket(realm, "99001", "99000", "chat-id", "signing", "encrypted", 3)
                assertEquals(ChatQueueResult.NEW, data.queueChat(packet))
                assertEquals(ChatQueueResult.DUPLICATE, data.queueChat(packet))
                assertEquals(ChatQueueResult.REJECTED, data.queueChat(packet.copy(envelope = "different")))
                data.ackChat(realm, "99000", "99001", packet.id)
                assertEquals(packet, data.pendingChat(realm, "99001").single())
                data.ackChat(realm, "99001", "99000", packet.id)
                data.recordChat(ChatNote(account, "99000", false, packet.id, "محليًا", 3, true))
            } finally {
                migrated.close()
            }
            val reopened = openLocalDatabase(context, name)
            try {
                assertTrue(reopened.data().missed(account).isEmpty())
                assertTrue(reopened.data().pendingMissed(realm, "99001").isEmpty())
                assertTrue(reopened.data().pendingChat(realm, "99001").isEmpty())
                assertEquals("محليًا", reopened.data().recentChat(account).single().body)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
