package d.d.meshenger

import android.app.Dialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import d.d.meshenger.MainService
import d.d.meshenger.MainService.MainBinder
import org.libsodium.jni.NaCl
import org.libsodium.jni.Sodium
import java.util.*

/*
 * Show splash screen, name setup dialog, database password dialog and
 * start background service before starting the MainActivity.
 */
class StartActivity : MeshengerActivity(), ServiceConnection {
    private var binder: MainBinder? = null
    private var dialog : Dialog? = null
    private var startState = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        // load libsodium for JNI access
        sodium = NaCl.sodium()

        val type = Typeface.createFromAsset(assets, "rounds_black.otf")
        findViewById<TextView>(R.id.splashText).setTypeface(type)

        // start MainService and call back via onServiceConnected()
        startService(Intent(this, MainService::class.java))
    }

    private fun continueInit() {
        startState += 1
        when (startState) {
            1 -> {
                Log.d(this, "init 1: load database")
                // open without password
                binder!!.getService().loadDatabase()
                continueInit()
            }
            2 -> {
                Log.d(this, "init 2: check database")
                if (binder!!.getDatabase() == null) {
                    // database is probably encrypted
                    showDatabasePasswordDialog()
                } else {
                    continueInit()
                }
            }
            3 -> {
                Log.d(this, "init 3: check username")
                if (binder!!.getSettings().username.isEmpty()) {
                    // set username
                    showMissingUsernameDialog()
                } else {
                    continueInit()
                }
            }
            4 -> {
                Log.d(this, "init 4: check key pair")
                if (binder!!.getSettings().publicKey.isEmpty()) {
                    // generate key pair
                    initKeyPair()
                }
                continueInit()
            }
            5 -> {
                Log.d(this, "init 5: check addresses")
                if (binder!!.getService().first_start) {
                    showMissingAddressDialog()
                } else {
                    continueInit()
                }
            }
            6 -> {
                Log.d(this, "init 6: start contact list")
                // set night mode
                val nightMode = binder!!.getSettings().nightMode
                AppCompatDelegate.setDefaultNightMode(
                    if (nightMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
                // all done - show contact list
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        binder = iBinder as MainBinder
        Log.d(this, "onServiceConnected")
        if (startState == 0) {
            if (binder!!.getService().first_start) {
                // show delayed splash page
                Handler().postDelayed({ continueInit() }, 1000)
            } else {
                // show contact list as fast as possible
                continueInit()
            }
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        binder = null
    }

    override fun onResume() {
        super.onResume()
        bindService(Intent(this, MainService::class.java), this, BIND_AUTO_CREATE)
    }

    override fun onPause() {
        super.onPause()
        unbindService(this)
    }

    private fun initKeyPair() {
        // create secret/public key pair
        val publicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
        val secretKey = ByteArray(Sodium.crypto_sign_secretkeybytes())
        Sodium.crypto_sign_keypair(publicKey, secretKey)
        val settings = binder!!.getSettings()
        settings.publicKey = publicKey
        settings.secretKey = secretKey
        try {
            binder!!.saveDatabase()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getDefaultAddress(): AddressEntry? {
        val addresses = Utils.collectAddresses()

        // preferable, since we can device fe80:: and other addresses from it
        val macAddress = addresses.firstOrNull { it.device.startsWith("wlan") && Utils.isMACAddress(it.address) }
        if (macAddress != null) {
            return macAddress
        }

        val fe80Address = addresses.firstOrNull { it.device.startsWith("wlan") && it.address.startsWith("fe80::") }
        if (fe80Address != null) {
            return fe80Address
        }

        return null
    }

    private fun showMissingAddressDialog() {
        val defaultAddress = getDefaultAddress()
        if (defaultAddress == null) {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Setup Address")
            builder.setMessage("No address of your WiFi card found. Enable WiFi now (no Internet needed) or skip to configure later.")
            builder.setPositiveButton(R.string.ok) { dialog: DialogInterface, _: Int ->
                showMissingAddressDialog()
                dialog.cancel()
            }
            builder.setNegativeButton(R.string.skip) { dialog: DialogInterface, _: Int ->
                dialog.cancel()
                // continue with out address configuration
                continueInit()
            }
            builder.show()
        } else {
            binder!!.getSettings().addresses = mutableListOf(defaultAddress.address)
            try {
                binder!!.saveDatabase()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            continueInit()
        }
    }

    // initial dialog to set the username
    private fun showMissingUsernameDialog() {
        val tw = TextView(this)
        tw.setText(R.string.name_prompt)
        //tw.setTextColor(Color.BLACK);
        tw.textSize = 20f
        tw.gravity = Gravity.CENTER_HORIZONTAL
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.addView(tw)
        val et = EditText(this)
        et.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        et.isSingleLine = true
        layout.addView(et)
        layout.setPadding(40, 80, 40, 40)
        //layout.setGravity(Gravity.CENTER_HORIZONTAL);
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.hello)
        builder.setView(layout)
        builder.setNegativeButton(R.string.skip) { _: DialogInterface?, _: Int ->
            binder!!.shutdown()
            finish()
        }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        builder.setPositiveButton(R.string.next) { _: DialogInterface?, _: Int -> }
        val dialog = builder.create()
        dialog.setOnShowListener { newDialog: DialogInterface ->
            val okButton = (newDialog as AlertDialog).getButton(
                AlertDialog.BUTTON_POSITIVE
            )
            et.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int,
                ) {
                    // nothing to do
                }

                override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {
                    // nothing to do
                }

                override fun afterTextChanged(editable: Editable) {
                    okButton.isClickable = editable.isNotEmpty()
                    okButton.alpha = if (editable.isNotEmpty()) 1.0f else 0.5f
                }
            })
            okButton.isClickable = false
            okButton.alpha = 0.5f
            if (et.requestFocus()) {
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                //imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        }
        dialog.show()
        // override handler (to be able to dismiss the dialog manually)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            imm.toggleSoftInput(0, InputMethodManager.HIDE_IMPLICIT_ONLY)
            val username = et.text.toString()
            if (Utils.isValidName(username)) {
                binder!!.getSettings().username = username
                try {
                    binder!!.saveDatabase()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                // close dialog
                dialog.dismiss()
                //dialog.cancel(); // needed?
                continueInit()
            } else {
                Toast.makeText(this, R.string.invalid_name, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            imm.toggleSoftInput(0, InputMethodManager.HIDE_IMPLICIT_ONLY)
            val username = generateRandomUserName()
            if (Utils.isValidName(username)) {
                binder!!.getSettings().username = username
                try {
                    binder!!.saveDatabase()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                // close dialog
                dialog.dismiss()
                //dialog.cancel(); // needed?
                continueInit()
            } else {
                Toast.makeText(this, R.string.invalid_name, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ask for database password
    private fun showDatabasePasswordDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_database_password)
        val passwordEditText = dialog.findViewById<EditText>(R.id.change_password_edit_textview)
        val exitButton = dialog.findViewById<Button>(R.id.change_password_cancel_button)
        val okButton = dialog.findViewById<Button>(R.id.change_password_ok_button)
        okButton.setOnClickListener {
            val password = passwordEditText.text.toString()
            binder!!.getService().database_password = password
            binder!!.getService().loadDatabase()
            if (binder!!.getDatabase() == null) {
                Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
            } else {
                // close dialog
                dialog.dismiss()
                continueInit()
            }
        }
        exitButton.setOnClickListener {
            // shutdown app
            dialog.dismiss()
            binder!!.shutdown()
            finish()
        }
        dialog.show()
    }

    companion object {
        private var sodium: Sodium? = null
    }

    private fun generateRandomUserName(): String {
        val user = getString(R.string.user)
        val id = UUID.randomUUID().toString().substring(0..6)
        return "$user-$id"
    }
}