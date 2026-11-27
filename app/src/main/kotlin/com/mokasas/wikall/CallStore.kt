package com.mokasas.wikall

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Entity(indices = [Index(value = ["serial"], unique = true)])
internal data class AssignedDevice(
    @PrimaryKey val number: String,
    val serial: String,
    val signing: String,
    val encryption: String,
    val activationId: String,
    val revoked: Boolean,
    val expiresAt: Long?,
    @ColumnInfo(defaultValue = "0") val activated: Boolean,
) {
    constructor(value: Enrollment) : this(value.number, value.serial, value.signing, value.encryption, value.activationId, value.revoked, value.expiresAt, value.activated)
    fun enrollment() = Enrollment(number, serial, signing, encryption, activationId, revoked, expiresAt, activated)
}

@Entity
internal data class ConnectionProfile(@PrimaryKey val slot: Int, val address: String, val pin: String, val credential: String)

@Entity
internal data class CallHistory(@PrimaryKey val id: String, val peer: String, val state: String, val endedAt: Long)

@Entity(primaryKeys = ["account", "id"])
internal data class IncomingMissed(
    val account: String,
    val id: String,
    val peer: String,
    val endedAt: Long,
    @ColumnInfo(defaultValue = "0") val read: Boolean = false,
)

@Entity(primaryKeys = ["realm", "recipient", "id"])
internal data class LocalMissedDelivery(
    val realm: String,
    val recipient: String,
    val id: String,
    val caller: String,
    val endedAt: Long,
    val delivered: Boolean = false,
)

@Entity(primaryKeys = ["realm", "recipient", "sender", "id"])
internal data class ChatPacket(
    val realm: String, val recipient: String, val sender: String, val id: String,
    val signing: String, val envelope: String, val createdAt: Long, val delivered: Boolean = false,
)

@Entity(primaryKeys = ["account", "peer", "outgoing", "id"])
internal data class ChatNote(
    val account: String, val peer: String, val outgoing: Boolean, val id: String,
    val body: String, val createdAt: Long, val accepted: Boolean,
)

internal enum class ChatQueueResult { NEW, DUPLICATE, REJECTED }

internal interface ChatLedger {
    fun queueChat(packet: ChatPacket): ChatQueueResult
    fun pendingChat(realm: String, number: String): List<ChatPacket>
    fun ackChat(realm: String, number: String, sender: String, id: String)
}

@Dao
internal abstract class LocalData {
    @Query("SELECT * FROM ChatPacket WHERE realm = :realm AND recipient = :recipient AND sender = :sender AND id = :id")
    abstract fun chatPacket(realm: String, recipient: String, sender: String, id: String): ChatPacket?

    @Query("SELECT COUNT(*) FROM ChatPacket WHERE realm = :realm AND recipient = :recipient AND delivered = 0")
    abstract fun queuedChatCount(realm: String, recipient: String): Int

    @Insert
    abstract fun insertChatPacket(packet: ChatPacket)

    @Transaction
    open fun queueChat(packet: ChatPacket): ChatQueueResult {
        val prior = chatPacket(packet.realm, packet.recipient, packet.sender, packet.id)
        if (prior != null) return if (prior.envelope == packet.envelope) ChatQueueResult.DUPLICATE else ChatQueueResult.REJECTED
        if (queuedChatCount(packet.realm, packet.recipient) >= 100) return ChatQueueResult.REJECTED
        insertChatPacket(packet)
        return ChatQueueResult.NEW
    }

    @Query("SELECT * FROM ChatPacket WHERE realm = :realm AND recipient = :number AND delivered = 0 ORDER BY createdAt LIMIT 100")
    abstract fun pendingChat(realm: String, number: String): List<ChatPacket>

    @Query("UPDATE ChatPacket SET delivered = 1 WHERE realm = :realm AND recipient = :number AND sender = :sender AND id = :id")
    abstract fun ackChat(realm: String, number: String, sender: String, id: String)

    @Query("DELETE FROM ChatPacket WHERE rowid IN (SELECT rowid FROM ChatPacket WHERE realm = :realm AND recipient = :number AND delivered = 1 ORDER BY createdAt DESC LIMIT -1 OFFSET 200)")
    abstract fun pruneChat(realm: String, number: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun recordChat(note: ChatNote)

    @Query("UPDATE ChatNote SET accepted = 1 WHERE account = :account AND peer = :peer AND outgoing = 1 AND id = :id")
    abstract fun acceptChat(account: String, peer: String, id: String)

    @Query("SELECT * FROM ChatNote WHERE account = :account ORDER BY createdAt DESC LIMIT 40")
    abstract fun recentChat(account: String): List<ChatNote>

    @Query("SELECT * FROM AssignedDevice ORDER BY number")
    abstract fun devices(): List<AssignedDevice>

    @Query("SELECT * FROM AssignedDevice WHERE number = :number")
    abstract fun device(number: String): AssignedDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveDevice(device: AssignedDevice)

    @Transaction
    open fun issue(request: DeviceRequest, preferredNumber: String?): Enrollment {
        val issued = allocate(devices().map(AssignedDevice::enrollment), request, preferredNumber)
        saveDevice(AssignedDevice(issued))
        return issued
    }

    @Query("UPDATE AssignedDevice SET activated = 1 WHERE number = :number AND activationId = :id AND revoked = 0 AND activated = 0 AND expiresAt > :now")
    abstract fun redeemActivation(number: String, id: String, now: Long): Int

    @Query("UPDATE AssignedDevice SET revoked = 1 WHERE number = :number")
    abstract fun revokeDevice(number: String)

    @Query("SELECT * FROM ConnectionProfile WHERE slot = 0")
    abstract fun profile(): ConnectionProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun saveProfile(profile: ConnectionProfile)

    @Query("DELETE FROM ConnectionProfile")
    abstract fun forgetProfile()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun record(call: CallHistory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun recordMissed(call: IncomingMissed): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun enqueueMissed(item: LocalMissedDelivery)

    @Query("SELECT * FROM LocalMissedDelivery WHERE realm = :realm AND recipient = :number AND delivered = 0 ORDER BY endedAt LIMIT 20")
    abstract fun pendingMissed(realm: String, number: String): List<LocalMissedDelivery>

    @Query("UPDATE LocalMissedDelivery SET delivered = 1 WHERE realm = :realm AND recipient = :number AND id = :id")
    abstract fun ackMissed(realm: String, number: String, id: String)

    @Query("DELETE FROM LocalMissedDelivery WHERE realm = :realm AND recipient = :number AND id NOT IN (SELECT id FROM LocalMissedDelivery WHERE realm = :realm AND recipient = :number ORDER BY endedAt DESC LIMIT 100)")
    abstract fun pruneMissed(realm: String, number: String)

    @Query("SELECT * FROM IncomingMissed WHERE account = :account AND read = 0 ORDER BY endedAt DESC LIMIT 20")
    abstract fun missed(account: String): List<IncomingMissed>

    @Query("UPDATE IncomingMissed SET read = 1 WHERE account = :account AND id = :id")
    abstract fun markRead(account: String, id: String)
}

@Database(
    entities = [AssignedDevice::class, ConnectionProfile::class, CallHistory::class, IncomingMissed::class, LocalMissedDelivery::class,
        ChatNote::class, ChatPacket::class],
    version = 7,
    exportSchema = false,
)
internal abstract class LocalDatabase : RoomDatabase() {
    abstract fun data(): LocalData
}

internal data class SavedConnection(val address: String?, val activation: Activation, val file: ByteArray) {
    val pin get() = activation.pin

    fun retainingNewerOverlay(other: SavedConnection?): SavedConnection =
        other?.takeIf { it.activation.canRefreshOverlay(activation) }?.copy(address = address) ?: this
}

internal fun openLocalDatabase(context: Context, name: String): LocalDatabase =
    Room.databaseBuilder(context, LocalDatabase::class.java, name)
        .addMigrations(object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS Device")
                db.execSQL("DELETE FROM ConnectionProfile")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS AssignedDevice (
                        number TEXT NOT NULL PRIMARY KEY, serial TEXT NOT NULL, signing TEXT NOT NULL,
                        encryption TEXT NOT NULL, activationId TEXT NOT NULL, revoked INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_AssignedDevice_serial ON AssignedDevice(serial)")
            }
        }, object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS IncomingMissed (
                        account TEXT NOT NULL, id TEXT NOT NULL, peer TEXT NOT NULL, endedAt INTEGER NOT NULL,
                        PRIMARY KEY(account, id)
                    )
                """.trimIndent())
            }
        }, object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE AssignedDevice ADD COLUMN expiresAt INTEGER")
            }
        }, object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS LocalMissedDelivery (
                        realm TEXT NOT NULL, recipient TEXT NOT NULL, id TEXT NOT NULL,
                        caller TEXT NOT NULL, endedAt INTEGER NOT NULL, delivered INTEGER NOT NULL,
                        PRIMARY KEY(realm, recipient, id)
                    )
                """.trimIndent())
            }
        }, object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE AssignedDevice ADD COLUMN activated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE IncomingMissed ADD COLUMN read INTEGER NOT NULL DEFAULT 0")
            }
        }, object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE ChatPacket (realm TEXT NOT NULL, recipient TEXT NOT NULL, sender TEXT NOT NULL, id TEXT NOT NULL, signing TEXT NOT NULL, envelope TEXT NOT NULL, createdAt INTEGER NOT NULL, delivered INTEGER NOT NULL, PRIMARY KEY(realm, recipient, sender, id))")
                db.execSQL("CREATE TABLE ChatNote (account TEXT NOT NULL, peer TEXT NOT NULL, outgoing INTEGER NOT NULL, id TEXT NOT NULL, body TEXT NOT NULL, createdAt INTEGER NOT NULL, accepted INTEGER NOT NULL, PRIMARY KEY(account, peer, outgoing, id))")
            }
        }).build()

@Singleton
internal class CallStore @Inject constructor(@ApplicationContext context: Context) : EnrollmentRegistry, ChatLedger {
    private val data = openLocalDatabase(context, "wi-call.db").data()

    override fun find(number: String) = data.device(number)?.enrollment()
    override fun revoke(number: String) = data.revokeDevice(number)
    override fun redeem(number: String, id: String): Boolean =
        data.redeemActivation(number, id, System.currentTimeMillis()) == 1
    fun devices() = data.devices().map(AssignedDevice::enrollment)
    fun issue(request: DeviceRequest, preferredNumber: String? = null) = data.issue(request, preferredNumber)

    fun saved(identity: DeviceIdentity): SavedConnection? = data.profile()?.let {
        val bytes = Wire.decode(it.credential)
        val activation = Activation.read(bytes, identity.decryption, identity.request.serial)
        SavedConnection(it.address.takeIf(::privateIpv4), activation, bytes)
    }

    fun save(connection: SavedConnection) =
        data.saveProfile(ConnectionProfile(0, connection.address.orEmpty(), "", Wire.encode(connection.file)))

    fun forget() = data.forgetProfile()
    fun recordChat(note: ChatNote) = data.recordChat(note)
    fun acceptChat(account: String, peer: String, id: String) = data.acceptChat(account, peer, id)
    fun recentChat(account: String) = data.recentChat(account)
    override fun queueChat(packet: ChatPacket) = data.queueChat(packet)
    override fun pendingChat(realm: String, number: String) = data.pendingChat(realm, number)
    override fun ackChat(realm: String, number: String, sender: String, id: String) {
        data.ackChat(realm, number, sender, id)
        data.pruneChat(realm, number)
    }
    fun record(call: CallHistory) = data.record(call)
    fun recordMissed(call: IncomingMissed) = data.recordMissed(call)
    fun missed(account: String) = data.missed(account)
    fun markRead(account: String, id: String) = data.markRead(account, id)
    override fun enqueueMissed(realm: String, number: String, call: java.util.UUID, caller: String) {
        data.enqueueMissed(LocalMissedDelivery(realm, number, call.toString(), caller, System.currentTimeMillis()))
        data.pruneMissed(realm, number)
    }

    override fun pendingMissed(realm: String, number: String): List<Signal> =
        data.pendingMissed(realm, number).map {
            Signal(SignalType.MISSED_CALL, java.util.UUID.fromString(it.id), "${it.caller},${it.endedAt}")
        }

    override fun ackMissed(realm: String, number: String, call: java.util.UUID) =
        data.ackMissed(realm, number, call.toString())
}
