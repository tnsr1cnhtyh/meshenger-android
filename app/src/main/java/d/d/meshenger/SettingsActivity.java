package d.d.meshenger;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import org.w3c.dom.Text;

import java.util.HashMap;
import java.util.Locale;

public class SettingsActivity extends AppCompatActivity {
    String nick;
    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        setTitle(getResources().getString(R.string.menu_settings));
        prefs = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        nick = prefs.getString("username", "undefined");

        findViewById(R.id.changeNickLayout).setOnClickListener((v) -> changeNick());
    }

    private void getLocale(){
        Configuration config = getResources().getConfiguration();
        Locale locale = config.locale;
        ((TextView) findViewById(R.id.localeTv)).setText(locale.getDisplayLanguage());

        Locale[] locales = new Locale[]{Locale.ENGLISH, Locale.GERMAN};
        findViewById(R.id.changeLocaleLayout).setOnClickListener((v) -> {
            RadioGroup group = new RadioGroup(this);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            for(int i = 0; i < locales.length; i++){
                Locale l = locales[i];
                RadioButton button = new RadioButton(this);
                button.setId(i);
                button.setText(l.getDisplayLanguage());
                if(l.getISO3Language().equals(locale.getISO3Language())) button.setChecked(true);
                group.addView(button);
            }
            builder.setView(group);
            AlertDialog dialog = builder.show();
            group.setOnCheckedChangeListener((a, position) -> {
                Log.d("Settings", "changed locale to " + locales[position].getLanguage());

                Configuration config1 = new Configuration();
                config1.locale = locales[position];

                getResources().updateConfiguration(config1, getResources().getDisplayMetrics());

                dialog.dismiss();
            });
        });
    }


    private void changeNick(){
        EditText et = new EditText(this);
        et.setText(nick);
        et.setSelection(nick.length());
        new AlertDialog.Builder(this)
                .setTitle(getResources().getString(R.string.settings_change_nick))
                .setView(et)
                .setPositiveButton("ok", (dialogInterface, i) -> {
                    nick = et.getText().toString();
                    prefs.edit().putString("username", nick).apply();
                    initViews();
                })
                .setNegativeButton(getResources().getText(R.string.cancel), null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        initViews();
    }

    private void initViews(){
        ((TextView) findViewById(R.id.nickTv)).setText(nick);
        getLocale();
    }
}
