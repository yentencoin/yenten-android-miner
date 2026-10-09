package com.example.ottylab.bitzenyminer;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;

import com.example.ottylab.bitzenymininglibrary.BitZenyMiningLibrary;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BitZenyMiner";
    private static final int LOG_LINES = 1000;
    private static final int THEME_DARK = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_MATRIX = 2;
    private static final String DEFAULT_POOL = "stratum+tcp://yenten-pool.info:63368";
    private static final String CPU_POOL = "stratum+tcp://cpu-pool.com:63368";

    private BitZenyMiningLibrary miner;
    private EditText editTextServer, editTextUser, editTextPassword, editTextNThreads;
    private Button buttonDrive;
    private CheckBox checkBoxBenchmark;
    private Spinner spinnerAlgorithm, spinnerPool, spinnerTheme, spinnerLanguage;
    private TextView textViewLog, textViewStatus;
    private View statusDot;
    private boolean running;
    private final BlockingQueue<String> logs = new LinkedBlockingQueue<>(LOG_LINES);

    private static class JNICallbackHandler extends Handler {
        private final WeakReference<MainActivity> activity;
        JNICallbackHandler(MainActivity activity) { this.activity = new WeakReference<>(activity); }

        @Override public void handleMessage(Message msg) {
            MainActivity owner = activity.get();
            if (owner != null) {
                String log = msg.getData().getString("log");
                owner.textViewLog.setText(Utils.rotateStringQueue(owner.logs, log));
                Log.d(TAG, log);
            }
        }
    }

    private static JNICallbackHandler sHandler;

    @Override protected void onCreate(Bundle savedInstanceState) {
        int theme = PreferenceManager.getDefaultSharedPreferences(this).getInt("ui_theme", THEME_DARK);
        setTheme(themeResource(theme));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        editTextServer = findViewById(R.id.editTextServer);
        editTextUser = findViewById(R.id.editTextUser);
        editTextPassword = findViewById(R.id.editTextPassword);
        editTextNThreads = findViewById(R.id.editTextNThreads);
        buttonDrive = findViewById(R.id.buttonDrive);
        checkBoxBenchmark = findViewById(R.id.checkBoxBenchmark);
        spinnerAlgorithm = findViewById(R.id.spinnerAlgorithm);
        spinnerPool = findViewById(R.id.spinnerPool);
        spinnerTheme = findViewById(R.id.spinnerTheme);
        spinnerLanguage = findViewById(R.id.spinnerLanguage);
        textViewStatus = findViewById(R.id.textViewStatus);
        statusDot = findViewById(R.id.statusDot);
        textViewLog = findViewById(R.id.textViewLog);
        textViewLog.setMovementMethod(new ScrollingMovementMethod());

        setupSpinner(spinnerAlgorithm, R.array.algorithms);
        setupSpinner(spinnerPool, R.array.pools);
        setupSpinner(spinnerTheme, R.array.themes);
        setupSpinner(spinnerLanguage, R.array.languages);

        sHandler = new JNICallbackHandler(this);
        miner = new BitZenyMiningLibrary(sHandler);
        restoreSetting();
        spinnerTheme.setSelection(theme);
        spinnerLanguage.setSelection(languageIndex(currentLanguageTag()));

        spinnerPool.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) editTextServer.setText(DEFAULT_POOL);
                else if (position == 1) editTextServer.setText(CPU_POOL);
                storeSetting();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int selected = PreferenceManager.getDefaultSharedPreferences(MainActivity.this).getInt("ui_theme", THEME_DARK);
                if (position != selected) {
                    PreferenceManager.getDefaultSharedPreferences(MainActivity.this).edit().putInt("ui_theme", position).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String language = position == 1 ? "ru" : (position == 2 ? "zh-CN" : "en");
                String current = AppCompatDelegate.getApplicationLocales().toLanguageTags();
                if (!language.equalsIgnoreCase(current)) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        buttonDrive.setOnClickListener(v -> toggleMining());
        changeState(miner.isMiningRunning());
        showDeviceInfo();
    }

    private void setupSpinner(Spinner spinner, int array) {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, array,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private int themeResource(int theme) {
        if (theme == THEME_LIGHT) return R.style.AppTheme_Light;
        if (theme == THEME_MATRIX) return R.style.AppTheme_Matrix;
        return R.style.AppTheme_Dark;
    }

    private String currentLanguageTag() {
        String appLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        if (!appLanguage.isEmpty()) return appLanguage;
        String deviceLanguage = Locale.getDefault().getLanguage();
        if ("ru".equals(deviceLanguage)) return "ru";
        if ("zh".equals(deviceLanguage)) return "zh-CN";
        return "en";
    }

    private int languageIndex(String languageTag) {
        if (languageTag.toLowerCase(Locale.ROOT).startsWith("ru")) return 1;
        if (languageTag.toLowerCase(Locale.ROOT).startsWith("zh")) return 2;
        return 0;
    }

    private void toggleMining() {
        if (running) {
            miner.stopMining();
            changeState(false);
            storeSetting();
            return;
        }

        int threads = 0;
        String threadText = editTextNThreads.getText().toString().trim();
        try {
            if (!threadText.isEmpty()) threads = Integer.parseInt(threadText);
        } catch (NumberFormatException e) {
            editTextNThreads.setError(getString(R.string.invalid_threads));
            return;
        }
        if (threads < 0) {
            editTextNThreads.setError(getString(R.string.invalid_threads));
            return;
        }
        if (!checkBoxBenchmark.isChecked()) {
            if (editTextServer.getText().toString().trim().isEmpty()) {
                editTextServer.setError(getString(R.string.required));
                return;
            }
            if (editTextUser.getText().toString().trim().isEmpty()) {
                editTextUser.setError(getString(R.string.wallet_required));
                return;
            }
        }

        BitZenyMiningLibrary.Algorithm algorithm = spinnerAlgorithm.getSelectedItemPosition() == 0
                ? BitZenyMiningLibrary.Algorithm.YESCRYPT : BitZenyMiningLibrary.Algorithm.YESPOWER;
        if (checkBoxBenchmark.isChecked()) {
            miner.startBenchmark(threads, algorithm);
        } else {
            miner.startMining(editTextServer.getText().toString().trim(), editTextUser.getText().toString().trim(),
                    editTextPassword.getText().toString(), threads, algorithm);
        }
        changeState(true);
        storeSetting();
    }

    private void changeState(boolean isRunning) {
        buttonDrive.setText(isRunning ? R.string.stop_mining : R.string.start_mining);
        textViewStatus.setText(isRunning ? R.string.status_mining : R.string.status_ready);
        statusDot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                isRunning ? R.color.status_running : R.color.status_idle)));
        editTextServer.setEnabled(!isRunning);
        editTextUser.setEnabled(!isRunning);
        editTextPassword.setEnabled(!isRunning);
        editTextNThreads.setEnabled(!isRunning);
        spinnerAlgorithm.setEnabled(!isRunning);
        spinnerPool.setEnabled(!isRunning);
        checkBoxBenchmark.setEnabled(!isRunning);
        running = isRunning;
    }

    private void storeSetting() {
        if (editTextServer == null) return;
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString("server", editTextServer.getText().toString());
        editor.putString("user", editTextUser.getText().toString());
        editor.putString("password", editTextPassword.getText().toString());
        editor.putString("n_threads", editTextNThreads.getText().toString());
        editor.putInt("algorithm", spinnerAlgorithm.getSelectedItemPosition());
        editor.putInt("pool", spinnerPool.getSelectedItemPosition());
        editor.apply();
    }

    private void restoreSetting() {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
        editTextServer.setText(pref.getString("server", DEFAULT_POOL));
        editTextUser.setText(pref.getString("user", ""));
        editTextPassword.setText(pref.getString("password", "x"));
        editTextNThreads.setText(pref.getString("n_threads", ""));
        spinnerAlgorithm.setSelection(pref.getInt("algorithm", 1));
        String server = editTextServer.getText().toString().toLowerCase();
        int pool = server.contains("yenten-pool.info") ? 0 : (server.contains("cpu-pool.com") ? 1 : 2);
        spinnerPool.setSelection(pool);
    }

    private void showDeviceInfo() {
        String[] keys = {"os.arch", "os.name", "os.version"};
        for (String key : keys) Log.d(TAG, key + ": " + System.getProperty(key));
        Log.d(TAG, "CODE NAME: " + Build.VERSION.CODENAME);
        Log.d(TAG, "SDK INT: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "MANUFACTURER: " + Build.MANUFACTURER);
        Log.d(TAG, "MODEL: " + Build.MODEL);
        Log.d(TAG, "PRODUCT: " + Build.PRODUCT);
    }
}
