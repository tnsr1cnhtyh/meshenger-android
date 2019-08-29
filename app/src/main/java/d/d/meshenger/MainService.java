package d.d.meshenger;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;
import org.libsodium.jni.Sodium;
import org.webrtc.SurfaceViewRenderer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.security.spec.ECField;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public class MainService extends Service implements Runnable {
    private Database db = null;
    private boolean first_start = false;
    private String database_path = "";
    private String database_password = "";

    public static final int serverPort = 10001;
    private ServerSocket server;

    private volatile boolean run = true;
    private RTCCall currentCall = null;

    @Override
    public void onCreate() {
        super.onCreate();

        this.database_path = this.getFilesDir() + "/database.bin";

        // handle incoming connections
        new Thread(this).start();

        LocalBroadcastManager.getInstance(this).registerReceiver(settingsReceiver, new IntentFilter("settings_changed"));
    }

    private void loadDatabase() {
        try {
            if ((new File(this.database_path)).exists()) {
                // open existing database
                this.db = Database.load(this.database_path, this.database_password);
                this.first_start = false;
            } else {
                // create new database
                this.db = new Database();
                this.first_start = true;
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void saveDatabase() {
        try {
            Database.store(MainService.this.database_path, MainService.this.db, MainService.this.database_password);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.run = false;

        // The database might be null here if no correct
        // database password was supplied to open it.

        if (this.db != null) {
            try {
                Database.store(this.database_path, this.db, this.database_password);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // shutdown listening socket and say goodbye
        if (this.db != null && this.server != null && this.server.isBound() && !this.server.isClosed()) {
            try {
                byte[] ownPublicKey = this.db.settings.getPublicKey();
                byte[] ownSecretKey = this.db.settings.getSecretKey();
                String message = "{\"action\": \"status_change\", \"status\", \"offline\"}";

                for (Contact contact : this.db.contacts) {
                    if (contact.getState() == Contact.State.OFFLINE) {
                        continue;
                    }

                    byte[] encrypted = Crypto.encryptMessage(message, contact.getPublicKey(), ownPublicKey, ownSecretKey);
                    if (encrypted == null) {
                        continue;
                    }

                    for (InetSocketAddress addr : contact.getAllSocketAddresses()) {
                        Socket socket = null;
                        try {
                            socket = new Socket(addr.getAddress(), addr.getPort());
                            PacketWriter pw = new PacketWriter(socket);
                            pw.writeMessage(encrypted);
                            //os.flush();
                            socket.close();
                            break;
                        } catch (Exception e) {
                            if (socket != null) {
                                try {
                                    socket.close();
                                } catch (Exception ee) {
                                    // ignore
                                }
                            }
                            socket = null;
                        }
                    }
                }

                server.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        LocalBroadcastManager.getInstance(this).unregisterReceiver(settingsReceiver);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void refreshContacts() {
        /*
        ArrayList<Contact> contacts = (ArrayList<Contact>) db.getContacts();
        if (db.getSettings() == null) {
            //userName = "Unknown";
            ignoreUnsaved = false;
        } else {
            //userName = db.getSettings().getUsername();
            if (db.getSettings().getBlockUC() == 1) {
                ignoreUnsaved = true;
            } else {
                ignoreUnsaved = false;
            }
        }
        */
    }

    private void handleClient(Socket client) {
        byte[] clientPublicKey = new byte[Sodium.crypto_sign_publickeybytes()];
        byte[] ownSecretKey = this.db.settings.getSecretKey();
        byte[] ownPublicKey = this.db.settings.getPublicKey();

        try {
            PacketWriter pw = new PacketWriter(client);
            PacketReader pr = new PacketReader(client);
            Contact contact = null;

            log("waiting for packet...");
            while (true) {
                byte[] request = pr.readMessage();
                if (request == null) {
                    log("timeout reached");
                    break;
                }

                String decrypted = Crypto.decryptMessage(request, clientPublicKey, ownPublicKey, ownSecretKey);
                if (decrypted == null) {
                    break;
                }

                if (contact == null) {
                    for (Contact c : this.db.contacts) {
                        if (Arrays.equals(c.getPublicKey(), clientPublicKey)) {
                            contact = c;
                        }
                    }

                    if (contact == null && this.db.settings.getBlockUnknown()) {
                        if (this.currentCall != null) {
                            this.currentCall.decline();
                        }
                        break;
                    }

                    if (contact != null && contact.getBlocked()) {
                        if (this.currentCall != null) {
                            this.currentCall.decline();
                        }
                        break;
                    }

                    if (contact == null) {
                        ArrayList<String> addresses = new ArrayList<>();
                        InetAddress address = ((InetSocketAddress) client.getRemoteSocketAddress()).getAddress();

                        // TODO: add full address and handle mac extraction when the contact is saved
                        if (address instanceof Inet6Address) {
                            // if the IPv6 address contains a MAC address, take that.
                            byte[] mac = Utils.getEUI64MAC((Inet6Address) address);
                            if (mac != null) {
                                addresses.add(Utils.bytesToMacAddress(mac));
                            } else {
                                addresses.add(address.getHostAddress());
                            }
                        } else {
                            addresses.add(address.getHostAddress());
                        }

                        contact = new Contact(getResources().getString(R.string.unknown_caller), clientPublicKey.clone(), addresses);
                    }
                } else {
                    if (!Arrays.equals(contact.getPublicKey(), clientPublicKey)) {
                        // suspicious change of identity in call...
                        continue;
                    }
                }

                JSONObject obj = new JSONObject(decrypted);
                String action = obj.optString("action", "");

                switch (action) {
                    case "call": {
                        // someone calls us
                        log("call...");
                        String offer = obj.getString("offer");
                        this.currentCall = new RTCCall(this, ownPublicKey, ownSecretKey, contact, client, offer);

                        // respond that we accept the call

                        byte[] encrypted = Crypto.encryptMessage("{\"action\":\"ringing\"}", contact.getPublicKey(), ownPublicKey, ownSecretKey);
                        pw.writeMessage(encrypted);

                        Intent intent = new Intent(this, CallActivity.class);
                        intent.setAction("ACTION_ACCEPT_CALL");
                        intent.putExtra("EXTRA_CONTACT", contact);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return;
                    }
                    case "ping": {
                        log("ping...");
                        // someone wants to know if we are online
                        setClientState(contact, Contact.State.ONLINE);
                        SocketAddress a = client.getRemoteSocketAddress();
                        if (a instanceof InetSocketAddress) {
                            contact.setLastGoodAddress(((InetSocketAddress) a).getAddress());
                        }

                        byte[] encrypted = Crypto.encryptMessage("{\"action\":\"pong\"}", contact.getPublicKey(), ownPublicKey, ownSecretKey);
                        pw.writeMessage(encrypted);
                        break;
                    }
                    case "status_change": {
                        if (obj.optString("status", "").equals("offline")) {
                            setClientState(contact, Contact.State.OFFLINE);
                        } else {
                            log("Received unknown status_change: " + obj.getString("status"));
                        }
                    }
                }
            }

            log("client disconnected");
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("call_declined"));
        } catch (Exception e) {
            e.printStackTrace();
            log("client disconnected (exception)");
            if (this.currentCall != null) {
                this.currentCall.decline();
            }
        }

        // zero out key
        Arrays.fill(clientPublicKey, (byte) 0);
    }

    private void setClientState(Contact contact, Contact.State state) {
        contact.setState(Contact.State.ONLINE);
        LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent("contact_refresh"));
    }

    @Override
    public void run() {
        try {
            // wait until database is ready
            while (this.db == null && this.run) {
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    break;
                }
            }

            server = new ServerSocket(serverPort);
            refreshContacts();
            while (this.run) {
                try {
                    Socket socket = server.accept();
                    new Thread(() -> handleClient(socket)).start();
                } catch (IOException e) {
                    // ignore
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            new Handler(getMainLooper()).post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            stopSelf();
            return;
        }
    }

    /*
    * Allows communication between MainService and other objects
    */
    class MainBinder extends Binder {
        RTCCall startCall(Contact contact, RTCCall.OnStateChangeListener listener, SurfaceViewRenderer renderer) {
            return RTCCall.startCall(
                MainService.this,
                MainService.this.db.settings.getPublicKey(),
                MainService.this.db.settings.getSecretKey(),
                contact,
                listener
            );
        }

        RTCCall getCurrentCall() {
            return currentCall;
        }

        boolean isFirstStart() {
            return MainService.this.first_start;
        }

        Contact getContactByPublicKey(byte[] pubKey) {
            for (Contact contact : getContacts()) {
                if (Arrays.equals(contact.getPublicKey(), pubKey)) {
                    return contact;
                }
            }
            return null;
        }

        Contact getContactByName(String name) {
            for (Contact contact : getContacts()) {
                if (contact.getName().equals(name)) {
                    return contact;
                }
            }
            return null;
        }

        void addContact(Contact contact) {
            db.addContact(contact);
            saveDatabase();
            refreshContacts();
        }

        void deleteContact(byte[] pubKey) {
            db.deleteContact(pubKey);
            saveDatabase();
            refreshContacts();
        }

        void shutdown() {
            MainService.this.stopSelf();
        }

        String getDatabasePassword() {
            return MainService.this.database_password;
        }

        void setDatabasePassword(String password) {
            MainService.this.database_password = password;
        }

        Database getDatabase() {
            return MainService.this.db;
        }

        void loadDatabase() {
            MainService.this.loadDatabase();
        }

        void replaceDatabase(Database db) {
            if (db != null) {
                if (MainService.this.db == null) {
                    MainService.this.db = db;
                } else {
                    MainService.this.db = db;
                    saveDatabase();
                }
            }
        }

        void pingContacts(ContactPingListener listener) {
            new Thread(new PingRunnable(
                getContacts(),
                getSettings().getPublicKey(),
                getSettings().getSecretKey(),
                listener)
            ).start();
        }

        void saveDatabase() {
            MainService.this.saveDatabase();
        }

        Settings getSettings() {
            return MainService.this.db.settings;
        }

        List<Contact> getContacts() {
            return MainService.this.db.contacts;
        }
    }

    class PingRunnable implements Runnable {
        private List<Contact> contacts;
        byte[] ownPublicKey;
        byte[] ownSecretKey;
        ContactPingListener listener;
        Socket socket;

        PingRunnable(List<Contact> contacts, byte[] ownPublicKey, byte[] ownSecretKey, ContactPingListener listener) {
            this.contacts = contacts;
            this.ownPublicKey = ownPublicKey;
            this.ownSecretKey = ownSecretKey;
            this.listener = listener;
            this.socket = new Socket();
        }

        @Override
        public void run() {
            for (Contact contact : contacts) {
                try {
                    Socket socket = contact.createSocket();
                    if (socket == null) {
                        contact.setState(Contact.State.OFFLINE);
                        continue;
                    }

                    byte[] encrypted = Crypto.encryptMessage("{\"action\":\"ping\"}", contact.getPublicKey(), ownPublicKey, ownSecretKey);
                    if (encrypted == null) {
                        continue;
                    }
                    PacketWriter pw = new PacketWriter(socket);
                    pw.writeMessage(encrypted);
                    socket.close();

                    contact.setState(Contact.State.ONLINE);
                } catch (Exception e) {
                    contact.setState(Contact.State.OFFLINE);
                    e.printStackTrace();
                } finally {
                    if (socket != null) {
                        try {
                            socket.close();
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    if (listener != null) {
                        listener.onContactPingResult(contact);
                    } else {
                        log("no listener!");
                    }
                }
            }
        }
    }

    private BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            /*
            switch (intent.getAction()) {
                case "settings_changed": {
                    String subject = intent.getStringExtra("subject");
                    switch (subject) {
                        case "username": {
                            userName = intent.getStringExtra("username");
                            log("username: " + userName);
                            break;
                        }
                        case "ignoreUnsaved":{
                            ignoreUnsaved = intent.getBooleanExtra("ignoreUnsaved", false);
                            log("ignore: " + ignoreUnsaved);
                            break;
                        }
                    }
                }
            }*/
        }
    };

    public interface ContactPingListener {
        void onContactPingResult(Contact c);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new MainBinder();
    }

    private static void log(String data) {
        Log.d(MainService.class.getSimpleName(), data);
    }
}
