package d.d.meshenger.call

import d.d.meshenger.*
import d.d.meshenger.AddressUtils
import org.json.JSONObject
import org.libsodium.jni.Sodium
import java.net.Socket

/*
 * Checks if a contact is online.
*/
class Pinger(val binder: MainService.MainBinder, val contacts: List<Contact>) : Runnable {
    private fun pingContact(contact: Contact) : Contact.State {
        Log.d(this, "pingContact() contact: ${contact.name}")

        val otherPublicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
        val settings = binder.getSettings()
        val ownPublicKey = settings.publicKey
        val ownSecretKey = settings.secretKey
        var socket: Socket? = null

        try {
            val connector = Connector(
                settings.connectTimeout,
                settings.connectRetries,
                settings.useNeighborTable,
                settings.guessEUI64Address
            )
            socket = connector.connect(contact)

            if (socket == null) {
                return if (connector.appNotRunning) {
                    Contact.State.APP_NOT_RUNNING
                } else if (connector.networkNotReachable) {
                    Contact.State.NETWORK_UNREACHABLE
                } else {
                    Contact.State.CONTACT_OFFLINE
                }
            }

            socket.soTimeout = 3000

            val pw = PacketWriter(socket)
            val pr = PacketReader(socket)

            Log.d(this, "pingContact() send ping to ${contact.name}")
            val encrypted = Crypto.encryptMessage(
                "{\"action\":\"ping\"}",
                contact.publicKey,
                ownPublicKey,
                ownSecretKey
            ) ?: return Contact.State.COMMUNICATION_FAILED

            pw.writeMessage(encrypted)
            val request = pr.readMessage() ?: return Contact.State.COMMUNICATION_FAILED
            val decrypted = Crypto.decryptMessage(
                request,
                otherPublicKey,
                ownPublicKey,
                ownSecretKey
            ) ?: return Contact.State.AUTHENTICATION_FAILED

            if (!otherPublicKey.contentEquals(contact.publicKey)) {
                return Contact.State.AUTHENTICATION_FAILED
            }

            val obj = JSONObject(decrypted)
            val action = obj.optString("action", "")
            if (action == "pong") {
                Log.d(this, "pingContact() got pong")
                return Contact.State.CONTACT_ONLINE
            } else {
                return Contact.State.COMMUNICATION_FAILED
            }
        } catch (e: Exception) {
            return Contact.State.COMMUNICATION_FAILED
        } finally {
            // make sure to close the socket
            AddressUtils.closeSocket(socket)
        }
    }

    override fun run() {
        // set all states to unknown
        for (contact in contacts) {
            binder.getContacts()
                .getContactByPublicKey(contact.publicKey)
                ?.state = Contact.State.PENDING
        }

        MainService.refreshContacts(binder.getService())
        MainService.refreshEvents(binder.getService())

        // ping contacts
        for (contact in contacts) {
            val state = pingContact(contact)
            Log.d(this, "contact state is $state")

            // set contact state
            binder.getContacts()
                .getContactByPublicKey(contact.publicKey)
                ?.state = state
        }

        MainService.refreshContacts(binder.getService())
        MainService.refreshEvents(binder.getService())
    }
}
