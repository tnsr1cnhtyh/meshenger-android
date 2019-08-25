package d.d.meshenger;

import android.app.Dialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatDelegate;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.libsodium.jni.Sodium;
import org.libsodium.jni.SodiumConstants;

import java.util.ArrayList;

import org.libsodium.jni.NaCl;


public class StartActivity extends MeshengerActivity implements ServiceConnection {
    private MainService.MainBinder binder;
    private int startState = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_splash);

        // load libsodium for JNI access
        NaCl.sodium();

        Typeface type = Typeface.createFromAsset(getAssets(), "rounds_black.otf");
        ((TextView) findViewById(R.id.splashText)).setTypeface(type);

        // start MainService and call back via onServiceConnected()
        startService(new Intent(this, MainService.class));
    }

    private void continueInit() {
        this.startState += 1;

        switch (this.startState) {
            case 1:
                log("init 1: load database");
                // open without password
                this.binder.loadDatabase();
                continueInit();
                break;
            case 2:
                log("init 2: check database");
                if (this.binder.getDatabase() == null) {
                    // database is probably encrypted
                    showDatabasePasswordDialog();
                } else {
                    continueInit();
                }
                break;
            case 3:
                log("init 3: check username");
                if (this.binder.getSettings().getUsername().isEmpty()) {
                    // set username
                    showMissingUsernameDialog();
                } else {
                    continueInit();
                }
                break;
            case 4:
                log("init 4: check key pair");
                if (this.binder.getSettings().getPublicKey().isEmpty()) {
                    // generate key pair
                    setKeyPair();
                }
                continueInit();
                break;
            case 5:
                log("init 5: check addresses");
                if (this.binder.isFirstStart()) {
                    showMissingAddressDialog();
                } else {
                    continueInit();
                }
                break;
            case 6:
               log("init 6: start contact list");
                // set night mode
                boolean nightMode = this.binder.getSettings().getNightMode();
                AppCompatDelegate.setDefaultNightMode(
                        nightMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO
                );

                // all done - show contact list
                startActivity(new Intent(this, ContactListActivity.class));
                finish();
                break;
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
        this.binder = (MainService.MainBinder) iBinder;

        if (this.binder.isFirstStart()) {
            // show delayed splash page
            (new Handler()).postDelayed(() -> { continueInit(); }, 1000);
        } else {
            // show contact list as fast as possible
            continueInit();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        this.binder = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindService(new Intent(this, MainService.class), this, Service.BIND_AUTO_CREATE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unbindService(this);
    }

    private void setKeyPair() {
        // create secret/public key pair
        byte[] publicKey = new byte[SodiumConstants.PUBLICKEY_BYTES];
        byte[] secretKey = new byte[SodiumConstants.SECRETKEY_BYTES];
        Sodium.crypto_box_keypair(publicKey, secretKey);

        Settings settings = this.binder.getSettings();
        settings.setPublicKey(Utils.byteArrayToHexString(publicKey).toUpperCase());
        settings.setSecretKey(Utils.byteArrayToHexString(secretKey).toUpperCase());

        try {
            this.binder.saveDatabase();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showMissingAddressDialog() {
        ArrayList<String> addresses = Utils.getMacAddresses();
        if (addresses.isEmpty()) {
            Toast.makeText(this, "No contact address found! Please configure.", Toast.LENGTH_LONG).show();
            continueInit();
        } else {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Setup Address");
            builder.setMessage("No address for your QR-Code configured. Set to: " + Utils.join(addresses));

            builder.setPositiveButton(R.string.ok, (DialogInterface dialog, int id) -> {
                for (String address : addresses) {
                    this.binder.getSettings().addAddress(address);
                }

                try {
                    this.binder.saveDatabase();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                dialog.cancel();
                continueInit();
            });

            builder.setNegativeButton(R.string.skip, (DialogInterface dialog, int id) -> {
                dialog.cancel();
                // continue with out address configuration
                continueInit();
            });

            builder.show();
        }
    }

    // initial dialog to set the username
    private void showMissingUsernameDialog() {
        TextView tw = new TextView(this);
        tw.setText(R.string.name_prompt);
        //tw.setTextColor(Color.BLACK);
        tw.setTextSize(20);
        tw.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(tw);

        EditText et = new EditText(this);
        et.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        et.setSingleLine(true);

        layout.addView(et);
        layout.setPadding(40, 80, 40, 40);
        //layout.setGravity(Gravity.CENTER_HORIZONTAL);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.hello);
        builder.setView(layout);
        builder.setNegativeButton(R.string.cancel, (dialogInterface, i) -> {
            this.binder.shutdown();
            finish();
        });

        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

        builder.setPositiveButton(R.string.next, (dialogInterface, i) -> {
            // we will override this handler
        });

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener((newDialog) -> {
            Button okButton = ((AlertDialog) newDialog).getButton(AlertDialog.BUTTON_POSITIVE);
            et.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                    // nothing to do
                }

                @Override
                public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                    // nothing to do
                }

                @Override
                public void afterTextChanged(Editable editable) {
                    okButton.setClickable(editable.length() > 0);
                    okButton.setAlpha(editable.length() > 0 ? 1.0f : 0.5f);
                }
            });

            okButton.setClickable(false);
            okButton.setAlpha(0.5f);

            if (et.requestFocus()) {
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
                //imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
            }
        });

        dialog.show();

        // override handler (to be able to dismiss the dialog manually)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((View v) -> {
            imm.toggleSoftInput(0, InputMethodManager.HIDE_IMPLICIT_ONLY);

            String username = et.getText().toString();
            this.binder.getSettings().setUsername(username);

            try {
                this.binder.saveDatabase();
            } catch (Exception e) {
                e.printStackTrace();
            }

            // close dialog
            dialog.dismiss();
            //dialog.cancel(); // needed?
            continueInit();
        });
    }

    // ask for database password
    private void showDatabasePasswordDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.database_password_dialog);
        //dialog.setTitle("Title...");

        EditText passwordEditText = dialog.findViewById(R.id.PasswordEditText);
        Button exitButton = dialog.findViewById(R.id.ExitButton);
        Button okButton = dialog.findViewById(R.id.OkButton);

        okButton.setOnClickListener((View v) -> {
            String password = passwordEditText.getText().toString();
            this.binder.setDatabasePassword(password);
            this.binder.loadDatabase();

            if (this.binder.getDatabase() == null) {
                Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show();
            } else {
                // close dialog
                dialog.dismiss();
                continueInit();
            }
        });

        exitButton.setOnClickListener((View v) -> {
            // shutdown app
            dialog.dismiss();
            this.binder.shutdown();
            finish();
        });

        dialog.show();
    }

    private void log(String s) {
        Log.d(StartActivity.class.getSimpleName(), s);
    }
}
