package d.d.meshenger

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import d.d.meshenger.Crypto.decryptMessage
import d.d.meshenger.Crypto.encryptMessage
import d.d.meshenger.Utils.readInternalFile
import d.d.meshenger.Utils.writeInternalFile
import d.d.meshenger.call.RTCCall
import org.json.JSONObject
import org.libsodium.jni.Sodium
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.*

class MainService : Service(), Runnable {
    private val binder = MainBinder()
    private var server: ServerSocket? = null
    private var database: Database? = null
    var first_start = false
    private var database_path = ""
    var database_password = ""

    @Volatile
    private var run = true
    private var currentCall: RTCCall? = null

    override fun onCreate() {
        super.onCreate()
        database_path = this.filesDir.toString() + "/database.bin"
        // handle incoming connections
        Thread(this).start()
    }

    private fun showNotification() {
        val channelId = "meshenger_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Meshenger Call Listener",
                NotificationManager.IMPORTANCE_LOW // display notification as collapsed by default
            )
            chan.lightColor = Color.RED
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val service = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            service.createNotificationChannel(chan)
        }
        // start MainActivity
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingNotificationIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.getActivity(this, 0, notificationIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            else PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT)
        val mActivity = applicationContext
        val notification = NotificationCompat.Builder(mActivity, channelId)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentText(resources.getText(R.string.listen_for_incoming_calls))
            .setContentIntent(pendingNotificationIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    fun loadDatabase() {
        if (File(database_path).exists()) {
            // open existing database
            val db = readInternalFile(database_path)
            database = Database.fromData(db, database_password)
            first_start = false
        } else {
            // create new database
            database = Database()
            first_start = true
        }
    }

    fun mergeDatabase(new_db: Database) {
        val old_database = database!!

        old_database.settings = new_db.settings

        for (contact in new_db.contacts.contactList) {
            old_database.contacts.addContact(contact)
        }

        for (event in new_db.events.eventList) {
            old_database.events.addEvent(event)
        }
    }

    fun saveDatabase() {
        try {
            val db = database
            if (db != null) {
                val dbData = Database.toData(db, database_password)
                if (dbData != null) {
                    writeInternalFile(database_path, dbData)
                }
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun createCommSocket(contact: Contact): Socket? {
        val addresses = contact.getAllSocketAddresses()
        Log.d(this, "addresses to try: " + addresses.joinToString())

        for (address in addresses) {
            Log.d(this, "try address: $address")
            val socket = AddressUtils.establishConnection(address)
            if (socket != null) {
                return socket
            }
        }
        return null
    }

    override fun onDestroy() {
        Log.d(this, "onDestroy")
        super.onDestroy()
        run = false

        // say goodbye
        val database = this.database
        if (database != null && server != null && server!!.isBound && !server!!.isClosed) {
            try {
                val ownPublicKey = database.settings.publicKey
                val ownSecretKey = database.settings.secretKey
                val message = "{\"action\": \"status_change\", \"status\", \"offline\"}"
                for (contact in database.contacts.contactList) {
                    if (contact.state === Contact.State.OFFLINE) {
                        continue
                    }
                    val encrypted = encryptMessage(message, contact.publicKey, ownPublicKey, ownSecretKey) ?: continue
                    var socket: Socket? = null
                    try {
                        socket = createCommSocket(contact)
                        if (socket == null) {
                            continue
                        }
                        val pw = PacketWriter(socket)
                        pw.writeMessage(encrypted)
                        socket.close()
                    } catch (e: Exception) {
                        if (socket != null) {
                            try {
                                socket.close()
                            } catch (ee: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // The database might be null here if no correct
        // database password was supplied to open it.
        if (database != null) {
            try {
                val data = Database.toData(database, database_password)
                if (data != null) {
                    writeInternalFile(database_path, data)
                }
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }

        // shutdown listening socket
        if (database != null && server != null && server!!.isBound && !server!!.isClosed) {
            try {
                server!!.close()
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }

        database?.destroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(this, "onStartCommand")

        if (intent == null || intent.action == null) {
            // ignore
        } else if (intent.action == START_FOREGROUND_ACTION) {
            Log.d(this, "Received Start Foreground Intent")
            showNotification()
        } else if (intent.action == STOP_FOREGROUND_ACTION) {
            Log.d(this, "Received Stop Foreground Intent")
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun run() {
        try {
            // wait until database is ready
            while (database == null && run) {
                try {
                    Thread.sleep(1000)
                } catch (e: Exception) {
                    break
                }
            }
            server = ServerSocket(serverPort)
            while (run) {
                try {
                    val socket = server!!.accept()
                    Thread { RTCCall.createIncomingCall(binder, socket) }.start()
                } catch (e: IOException) {
                    // ignore
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Handler(mainLooper).post { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
            stopSelf()
            return
        }
    }

    /*
    * Allows communication between MainService and other objects
    */
    inner class MainBinder : Binder() {
        fun getService(): MainService {
            return this@MainService
        }

        fun isDatabaseLoaded(): Boolean {
            return this@MainService.database != null
        }

        fun getDatabase(): Database {
            if (this@MainService.database == null) {
                Log.w(this, "database is null => try to reload")
                try {
                    // database is null, this should not happen, but
                    // happens anyway, so let's mitigate it for now
                    // => try to reload it
                    this@MainService.loadDatabase()
                } catch (e: Exception) {
                    Log.e(this, "failed to reload database")
                }
            }
            return this@MainService.database!!
        }

        fun getSettings(): Settings {
            return getDatabase().settings
        }

        fun getContacts(): Contacts {
            return getDatabase().contacts
        }

        fun getEvents(): Events {
            return getDatabase().events
        }

        fun getCurrentCall(): RTCCall? {
            return currentCall
        }

        fun addContact(contact: Contact) {
            getDatabase().contacts.addContact(contact)
            saveDatabase()

            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_contact_list"))
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }

        fun deleteContact(pubKey: ByteArray) {
            getDatabase().contacts.deleteContact(pubKey)
            saveDatabase()

            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_contact_list"))
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }

        fun shutdown() {
            this@MainService.stopSelf()
        }

        fun pingContacts() {
            val settings = getSettings()
            val contactList = getContacts().contactList
            Thread(
                PingRunnable(
                    this@MainService,
                    contactList,
                    settings.publicKey,
                    settings.secretKey
                )
            ).start()
        }

        fun saveDatabase() {
            this@MainService.saveDatabase()
        }

        internal fun addEvent(contact: Contact, type: Event.Type) {
            getEvents().addEvent(contact, type)
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }

        fun clearEvents() {
            getEvents().clearEvents()
            LocalBroadcastManager.getInstance(this@MainService)
                .sendBroadcast(Intent("refresh_event_list"))
        }
    }

    internal inner class PingRunnable(
        var context: Context,
        private val contacts: List<Contact>,
        var ownPublicKey: ByteArray?,
        var ownSecretKey: ByteArray?,
    ) : Runnable {
        private fun setState(publicKey: ByteArray, state: Contact.State) {
            val contact = binder.getContacts().getContactByPublicKey(publicKey)
            if (contact != null) {
                contact.state = state
            }
        }

        override fun run() {
            for (contact in contacts) {
                var socket: Socket? = null
                val publicKey = contact.publicKey
                try {
                    socket = createCommSocket(contact)
                    if (socket == null) {
                        setState(publicKey, Contact.State.OFFLINE)
                        continue
                    }
                    val pw = PacketWriter(socket)
                    val pr = PacketReader(socket)
                    Log.d(this, "send ping to ${contact.name}")
                    val encrypted = encryptMessage(
                        "{\"action\":\"ping\"}",
                        publicKey,
                        ownPublicKey!!,
                        ownSecretKey
                    )
                    if (encrypted == null) {
                        socket.close()
                        continue
                    }
                    pw.writeMessage(encrypted)
                    val request = pr.readMessage()
                    if (request == null) {
                        socket.close()
                        continue
                    }
                    val decrypted = decryptMessage(request, publicKey, ownPublicKey, ownSecretKey)
                    if (decrypted == null) {
                        Log.d(this, "decryption failed")
                        socket.close()
                        continue
                    }
                    val obj = JSONObject(decrypted)
                    val action = obj.optString("action", "")
                    if (action == "pong") {
                        Log.d(this, "got pong")
                        setState(publicKey, Contact.State.ONLINE)
                    }
                    socket.close()
                } catch (e: Exception) {
                    setState(publicKey, Contact.State.OFFLINE)
                    if (socket != null) {
                        try {
                            socket.close()
                        } catch (ee: Exception) {
                            // ignore
                        }
                    }
                    e.printStackTrace()
                }
            }

            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("refresh_contact_list"))
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent("refresh_event_list"))
        }

    }

    override fun onBind(intent: Intent): IBinder? {
        return binder
    }

    companion object {
        const val serverPort = 10001
        private const val START_FOREGROUND_ACTION = "START_FOREGROUND_ACTION"
        private const val STOP_FOREGROUND_ACTION = "STOP_FOREGROUND_ACTION"
        private const val NOTIFICATION_ID = 42

        fun start(ctx: Context) {
            val startIntent = Intent(ctx, MainService::class.java)
            startIntent.action = START_FOREGROUND_ACTION
            ContextCompat.startForegroundService(ctx, startIntent)
        }

        fun stop(ctx: Context) {
            val stopIntent = Intent(ctx, MainService::class.java)
            stopIntent.action = STOP_FOREGROUND_ACTION
            ctx.startService(stopIntent)
        }
    }
}