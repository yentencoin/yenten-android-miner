package com.example.ottylab.bitzenyminer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.widget.NestedScrollView;

import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BitZenyMiner";
    private static final int LOG_LINES = 1000;
    private static final int REQUEST_NOTIFICATIONS = 931;
    private static final int THEME_DARK = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_MATRIX = 2;
    private static final String DEFAULT_POOL = "stratum+tcp://yenten-pool.info:63368";
    private static final String CPU_POOL = "stratum+tcp://cpu-pool.com:63368";

    private final BlockingQueue<String> logs = new LinkedBlockingQueue<>(LOG_LINES);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable temperatureRefresh = new Runnable() {
        @Override public void run() {
            refreshCpuTemperature();
            mainHandler.postDelayed(this, 5000);
        }
    };

    private EditText editTextServer, editTextUser, editTextPassword, editTextNThreads;
    private Button buttonDrive;
    private CheckBox checkBoxBenchmark;
    private Spinner spinnerAlgorithm, spinnerPool, spinnerTheme, spinnerLanguage;
    private TextView textViewLog, textViewStatus, textViewCpuModel, textViewCpuCores, textViewCpuTemperature;
    private View statusDot;
    private NestedScrollView logScrollView;
    private boolean running;
    private boolean receiverRegistered;
    private Intent pendingMiningIntent;

    private final BroadcastReceiver miningReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (MiningService.ACTION_STATE.equals(intent.getAction())) {
                changeState(intent.getBooleanExtra("running", false));
            } else if (MiningService.ACTION_LOG.equals(intent.getAction())) {
                appendLog(intent.getStringExtra("log"));
            }
        }
    };

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
        logScrollView = findViewById(R.id.logScrollView);
        textViewCpuModel = findViewById(R.id.textViewCpuModel);
        textViewCpuCores = findViewById(R.id.textViewCpuCores);
        textViewCpuTemperature = findViewById(R.id.textViewCpuTemperature);

        setupSpinner(spinnerAlgorithm, R.array.algorithms);
        setupSpinner(spinnerPool, R.array.pools);
        setupSpinner(spinnerTheme, R.array.themes);
        setupSpinner(spinnerLanguage, R.array.languages);

        restoreSetting();
        spinnerTheme.setSelection(theme);
        spinnerLanguage.setSelection(languageIndex(currentLanguageTag()));
        textViewCpuModel.setText(DeviceInfo.cpuModel());
        textViewCpuCores.setText(DeviceInfo.coreSummary(this));
        refreshCpuTemperature();

        spinnerPool.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) editTextServer.setText(DEFAULT_POOL);
                else if (position == 1) editTextServer.setText(CPU_POOL);
                storeSetting();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        spinnerTheme.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                int selected = PreferenceManager.getDefaultSharedPreferences(MainActivity.this)
                        .getInt("ui_theme", THEME_DARK);
                if (position != selected) {
                    PreferenceManager.getDefaultSharedPreferences(MainActivity.this).edit()
                            .putInt("ui_theme", position).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        spinnerLanguage.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String language = position == 1 ? "ru" : (position == 2 ? "zh-CN" : "en");
                String current = AppCompatDelegate.getApplicationLocales().toLanguageTags();
                if (!language.equalsIgnoreCase(current)) {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        buttonDrive.setOnClickListener(view -> toggleMining());
        changeState(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("mining_running", false));
        showDeviceInfo();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MiningService.ACTION_STATE);
        filter.addAction(MiningService.ACTION_LOG);
        ContextCompat.registerReceiver(this, miningReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;

        String previousLogs = MiningService.recentLogs();
        logs.clear();
        if (!previousLogs.isEmpty()) {
            for (String line : previousLogs.split("(?<=\\n)")) {
                if (!line.isEmpty()) Utils.rotateStringQueue(logs, line);
            }
            textViewLog.setText(previousLogs);
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        }
        changeState(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("mining_running", false));
        mainHandler.post(temperatureRefresh);
    }

    @Override protected void onStop() {
        mainHandler.removeCallbacks(temperatureRefresh);
        if (receiverRegistered) {
            unregisterReceiver(miningReceiver);
            receiverRegistered = false;
        }
        super.onStop();
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
        String language = languageTag.toLowerCase(Locale.ROOT);
        if (language.startsWith("ru")) return 1;
        if (language.startsWith("zh")) return 2;
        return 0;
    }

    private void toggleMining() {
        if (running) {
            Intent stop = new Intent(this, MiningService.class).setAction(MiningService.ACTION_STOP);
            startService(stop);
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

        int algorithm = spinnerAlgorithm.getSelectedItemPosition();
        Intent start = new Intent(this, MiningService.class).setAction(MiningService.ACTION_START)
                .putExtra(MiningService.EXTRA_SERVER, editTextServer.getText().toString().trim())
                .putExtra(MiningService.EXTRA_USER, editTextUser.getText().toString().trim())
                .putExtra(MiningService.EXTRA_PASSWORD, editTextPassword.getText().toString())
                .putExtra(MiningService.EXTRA_THREADS, threads)
                .putExtra(MiningService.EXTRA_ALGORITHM, algorithm)
                .putExtra(MiningService.EXTRA_BENCHMARK, checkBoxBenchmark.isChecked());
        storeSetting();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingMiningIntent = start;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        launchMiningService(start);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS || pendingMiningIntent == null) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show();
        }
        launchMiningService(pendingMiningIntent);
        pendingMiningIntent = null;
    }

    private void launchMiningService(Intent intent) {
        ContextCompat.startForegroundService(this, intent);
        changeState(true);
    }

    private void appendLog(String line) {
        if (line == null) return;
        boolean wasAtBottom = !logScrollView.canScrollVertically(1);
        textViewLog.setText(Utils.rotateStringQueue(logs, line));
        if (wasAtBottom) logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        Log.d(TAG, line);
    }

    private void refreshCpuTemperature() {
        String temperature = DeviceInfo.cpuTemperature();
        textViewCpuTemperature.setText(temperature == null
                ? getString(R.string.device_info_unavailable) : temperature);
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
        String server = editTextServer.getText().toString().toLowerCase(Locale.ROOT);
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
