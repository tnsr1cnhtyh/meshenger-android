/*
* Copyright (C) 2025 Meshenger Contributors
* SPDX-License-Identifier: GPL-3.0-or-later
*/

package d.d.meshenger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.TranslateAnimation
import android.widget.AdapterView
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONException

class ContactListFragment() : Fragment() {
    private var binder: MainService.MainBinder = MainActivity.binder!!
    private lateinit var contactListView: ListView
    private lateinit var fabScan: FloatingActionButton
    private lateinit var fabGen: FloatingActionButton
    private lateinit var fabPingAll: FloatingActionButton
    private lateinit var fab: FloatingActionButton
    private var fabExpanded = false

    private val onContactClickListener =
        AdapterView.OnItemClickListener { adapterView, _, i, _ ->
            Log.d(this, "onItemClick")
            val activity = requireActivity()
            val contact = adapterView.adapter.getItem(i) as Contact
            if (contact.addresses.isEmpty()) {
                Toast.makeText(activity, R.string.contact_has_no_address_warning, Toast.LENGTH_SHORT).show()
            } else {
                Log.d(this, "start CallActivity")
                val intent = Intent(activity, CallActivity::class.java)
                intent.action = "ACTION_OUTGOING_CALL"
                intent.putExtra("EXTRA_CONTACT", contact)
                startActivity(intent)
            }
    }

    private val onContactLongClickListener =
        AdapterView.OnItemLongClickListener { adapterView, view, i, _ ->
            val contact = adapterView.adapter.getItem(i) as Contact
            val menu = PopupMenu(activity, view)
            // menu items
            val titles = intArrayOf(
                R.string.contact_menu_details, R.string.contact_menu_delete,
                R.string.contact_menu_ping, R.string.contact_menu_share,
                R.string.contact_menu_qrcode, R.string.contact_menu_details)

            for (titleRes in titles) {
                menu.menu.add(0, titleRes,0, titleRes)
            }

            menu.setOnMenuItemClickListener { menuItem: MenuItem ->
                val publicKey = contact.publicKey
                when (menuItem.itemId) {
                    R.string.contact_menu_details -> {
                        val intent = Intent(activity, ContactDetailsActivity::class.java)
                        intent.putExtra("EXTRA_CONTACT_PUBLICKEY", Utils.byteArrayToHexString(contact.publicKey))
                        startActivity(intent)
                    }
                    R.string.contact_menu_delete -> showDeleteDialog(publicKey, contact.name)
                    R.string.contact_menu_ping -> pingContact(contact)
                    R.string.contact_menu_share -> shareContact(contact)
                    R.string.contact_menu_qrcode -> {
                        val intent = Intent(activity, QRShowActivity::class.java)
                        intent.putExtra("EXTRA_CONTACT_PUBLICKEY", Utils.byteArrayToHexString(contact.publicKey))
                        startActivity(intent)
                    }
                }
                false
            }
            menu.show()
            true
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(this, "onCreateView()")
        val view: View = inflater.inflate(R.layout.fragment_contact_list, container, false)

        fab = view.findViewById(R.id.fab)
        fabScan = view.findViewById(R.id.fabScan)
        fabGen = view.findViewById(R.id.fabGenerate)
        fabPingAll = view.findViewById(R.id.fabPing)
        contactListView = view.findViewById(R.id.contactList)
        contactListView.onItemClickListener = onContactClickListener

        if (binder.getSettings().hideMenus) {
            fab.visibility = View.GONE
            contactListView.onItemLongClickListener = null
        } else {
            fab.visibility = View.VISIBLE
            contactListView.onItemLongClickListener = onContactLongClickListener
        }

        fabScan.setOnClickListener {
            val intent = Intent(activity, QRScanActivity::class.java)
            startActivity(intent)
        }

        fabGen.setOnClickListener {
            val intent = Intent(requireContext(), QRShowActivity::class.java)
            intent.putExtra("EXTRA_CONTACT_PUBLICKEY", Utils.byteArrayToHexString(binder.getSettings().publicKey))
            startActivity(intent)
        }

        fabPingAll.setOnClickListener {
            pingAllContacts()
            collapseFab()
        }

        fab.setOnClickListener { fab: View -> runFabAnimation(fab) }

        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(refreshContactListReceiver, IntentFilter("refresh_contact_list"))

        refreshContactListBroadcast()

        return view
    }

    private val refreshContactListReceiver = object : BroadcastReceiver() {
        //private var lastTimeRefreshed = 0L

        override fun onReceive(context: Context, intent: Intent) {
            Log.d(this@ContactListFragment, "trigger refreshContactList() from broadcast at ${this@ContactListFragment.lifecycle.currentState}")
            // prevent this method from being called too often
            //val now = System.currentTimeMillis()
            //if ((now - lastTimeRefreshed) > 1000) {
            //    lastTimeRefreshed = now
                refreshContactList()
            //}
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(refreshContactListReceiver)
        super.onDestroy()
    }

    override fun onResume() {
        Log.d(this, "onResume()")
        super.onResume()

        if (binder.getSettings().automaticStatusUpdates) {
            // ping all contacts
            binder.pingContacts(binder.getContacts().contactList)
        }

        MainService.refreshContacts(requireActivity())
    }

    private fun showPingAllButton(): Boolean {
        return !binder.getSettings().automaticStatusUpdates
    }

    private fun runFabAnimation(fab: View) {
        Log.d(this, "runFabAnimation")
        val activity = requireActivity()
        val scanSet = AnimationSet(activity, null)
        val showSet = AnimationSet(activity, null)
        val pingSet = AnimationSet(activity, null)
        val distance = 200f
        val duration = 300f
        val scanAnimation: TranslateAnimation
        val showAnimation: TranslateAnimation
        val pingAnimation: TranslateAnimation
        val alphaAnimation: AlphaAnimation

        if (fabExpanded) {
            pingAnimation = TranslateAnimation(0f, 0f, -distance * 1, 0f)
            scanAnimation = TranslateAnimation(0f, 0f, -distance * 2, 0f)
            showAnimation = TranslateAnimation(0f, 0f, -distance * 3, 0f)
            alphaAnimation = AlphaAnimation(1.0f, 0.0f)
            (fab as FloatingActionButton).setImageResource(R.drawable.qr_glass)
            fabGen.y = fabGen.y + distance * 1
            fabScan.y = fabScan.y + distance * 2
            fabPingAll.y = fabPingAll.y + distance * 3
        } else {
            pingAnimation = TranslateAnimation(0f, 0f, distance * 1, 0f)
            scanAnimation = TranslateAnimation(0f, 0f, distance * 2, 0f)
            showAnimation = TranslateAnimation(0f, 0f, distance * 3, 0f)
            alphaAnimation = AlphaAnimation(0.0f, 1.0f)
            (fab as FloatingActionButton).setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            fabGen.y = fabGen.y - distance * 1
            fabScan.y = fabScan.y - distance * 2
            fabPingAll.y = fabPingAll.y - distance * 3
        }

        scanSet.addAnimation(scanAnimation)
        scanSet.addAnimation(alphaAnimation)
        scanSet.fillAfter = true
        scanSet.duration = duration.toLong()

        showSet.addAnimation(showAnimation)
        showSet.addAnimation(alphaAnimation)
        showSet.fillAfter = true
        showSet.duration = duration.toLong()

        pingSet.addAnimation(pingAnimation)
        pingSet.addAnimation(alphaAnimation)
        pingSet.fillAfter = true
        pingSet.duration = duration.toLong()

        fabGen.visibility = View.VISIBLE
        fabScan.visibility = View.VISIBLE
        if (showPingAllButton()) {
            fabPingAll.visibility = View.VISIBLE
        }

        fabScan.startAnimation(scanSet)
        fabGen.startAnimation(showSet)
        fabPingAll.startAnimation(pingSet)

        fabExpanded = !fabExpanded
    }

    private fun collapseFab() {
        if (fabExpanded) {
            fab.setImageResource(R.drawable.qr_glass)
            fabScan.clearAnimation()
            fabGen.clearAnimation()
            fabPingAll.clearAnimation()

            fabGen.y = fabGen.y + 200 * 1
            fabScan.y = fabScan.y + 200 * 2
            if (showPingAllButton()) {
                fabPingAll.y = fabPingAll.y + 200 * 3
            }
            fabExpanded = false
        }
    }

    private fun pingContact(contact: Contact) {
        binder.pingContacts(listOf(contact))
        val message = String.format(getString(R.string.ping_contact), contact.name)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun pingAllContacts() {
        binder.pingContacts(binder.getContacts().contactList)
        val message = String.format(getString(R.string.ping_all_contacts))
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteDialog(publicKey: ByteArray, name: String) {
        val builder = AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
        builder.setTitle(R.string.dialog_title_delete_contact)
        builder.setMessage(name)
        builder.setCancelable(false) // prevent key shortcut to cancel dialog
        builder.setPositiveButton(R.string.button_yes) { dialog: DialogInterface, _: Int ->
                binder.deleteContact(publicKey)
                dialog.cancel()
            }

        builder.setNegativeButton(R.string.button_no) { dialog: DialogInterface, _: Int ->
            dialog.cancel() }

        // create dialog box
        val alert = builder.create()
        alert.show()
    }

    private fun refreshContactList() {
        Log.d(this, "refreshContactList")

        val contacts = binder.getContacts().contactList
        val activity = requireActivity()

        activity.runOnUiThread {
            contactListView.adapter = ContactListAdapter(activity, R.layout.item_contact, contacts)
        }
    }

    private fun refreshContactListBroadcast() {
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(Intent("refresh_contact_list"))
    }

    private fun shareContact(contact: Contact) {
        Log.d(this, "shareContact")
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, Contact.toJSON(contact, false).toString())
            startActivity(intent)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        collapseFab()
    }
}
