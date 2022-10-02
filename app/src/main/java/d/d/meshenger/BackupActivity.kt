package d.d.meshenger

import android.app.Activity
import d.d.meshenger.Utils.writeExternalFile
import d.d.meshenger.Utils.readExternalFile
import android.widget.ImageButton
import android.widget.TextView
import android.os.Bundle
import android.content.Intent
import d.d.meshenger.MainService.MainBinder
import android.widget.Toast
import android.content.ComponentName
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.view.View
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import java.lang.Exception

class BackupActivity : MeshengerActivity(), ServiceConnection {
    private var builder: AlertDialog.Builder? = null
    private var binder: MainBinder? = null
    private lateinit var exportButton: Button
    private lateinit var importButton: Button
    private lateinit var selectButton: ImageButton
    private lateinit var passwordEditText: TextView

    private fun showMessage(title: String, message: String) {
        builder!!.setTitle(title)
        builder!!.setMessage(message)
        builder!!.setPositiveButton(android.R.string.ok, null)
        builder!!.show()
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)

        val toolbar = findViewById<Toolbar>(R.id.backup_toolbar)
        toolbar.apply {
            setNavigationOnClickListener {
                finish()
            }
        }
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
        bindService()
        initViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (binder != null) {
            unbindService(this)
        }
    }

    private fun bindService() {
        // ask MainService to get us the binder object
        val serviceIntent = Intent(this, MainService::class.java)
        bindService(serviceIntent, this, BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
        binder = iBinder as MainBinder
        initViews()
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        binder = null
    }

    private fun initViews() {
        if (binder == null) {
            return
        }

        builder = AlertDialog.Builder(this)
        importButton = findViewById(R.id.ImportButton)
        exportButton = findViewById(R.id.ExportButton)
        selectButton = findViewById(R.id.SelectButton)
        passwordEditText = findViewById(R.id.PasswordEditText)
        importButton.setOnClickListener(View.OnClickListener { _: View? ->
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "application/json"
            importFileLauncher.launch(intent)
        })
        exportButton.setOnClickListener(View.OnClickListener { _: View? ->
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.putExtra(Intent.EXTRA_TITLE, "meshenger-backup.json")
            intent.type = "application/json"
            exportFileLauncher.launch(intent)
        })
    }

    private var importFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data ?: return@registerForActivityResult
            val uri = intent.data ?: return@registerForActivityResult
            importDatabase(uri)
        }
    }

    private var exportFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data ?: return@registerForActivityResult
            val uri: Uri = intent.data ?: return@registerForActivityResult
            exportDatabase(uri)
        }
    }

    private fun exportDatabase(uri: Uri) {
        val password = passwordEditText.text.toString()
        try {
            val database = binder!!.getDatabase()!!
            val data = Database.toData(database, password)

            if (data != null) {
                writeExternalFile(this, uri, data)
                Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.failed_to_export_database), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            showMessage(getString(R.string.error), e.message ?: "unknown")
        }
    }

    private fun importDatabase(uri: Uri) {
        val password = passwordEditText.text.toString()
        try {
            val data = readExternalFile(this, uri)
            val db = Database.fromData(data, password)

            binder!!.getService().replaceDatabase(db)
            val contactCount = db.contacts.contactList.size
            val eventCount = db.events.eventList.size

            showMessage(getString(R.string.done), "Imported $contactCount contacts and $eventCount events")
        } catch (e: Exception) {
            showMessage(getString(R.string.error), e.toString())
        }
    }
}