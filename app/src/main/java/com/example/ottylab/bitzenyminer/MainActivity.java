package com.example.ottylab.bitzenyminer;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.app.AlertDialog;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.os.LocaleListCompat;
import androidx.core.widget.NestedScrollView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BitZenyMiner";
    private static final int LOG_LINES = 1000;
    private static final int REQUEST_NOTIFICATIONS = 931;
    private static final int REQUEST_SAVE_WALLET = 932;
    private static final int THEME_DARK = 0;
    private static final int THEME_LIGHT = 1;
    private static final int THEME_MATRIX = 2;
    private static final String DEFAULT_POOL = "stratum+tcp://yenten-pool.info:63368";
    private static final String CPU_POOL = "stratum+tcp://cpu-pool.com:63368";
    private static final String RPLANT_EU_POOL = "stratum+tcp://eu.rplant.xyz:3382";

    private final BlockingQueue<String> logs = new LinkedBlockingQueue<>(LOG_LINES);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable temperatureRefresh = new Runnable() {
        @Override public void run() {
            refreshCpuTemperature();
            mainHandler.postDelayed(this, 5000);
        }
    };

    private EditText editTextServer, editTextUser, editTextPassword, editTextNThreads;
    private TextInputLayout walletInputLayout;
    private Button buttonDrive;
    private Button buttonNavMining, buttonNavWallet, buttonNavSettings;
    private View pageMining, pageWallet, pageSettings;
    private TextView textViewScreenTitle;
    private CheckBox checkBoxBenchmark;
    private Spinner spinnerAlgorithm, spinnerPool, spinnerTheme, spinnerLanguage;
    private TextView textViewLog, textViewStatus, textViewCpuModel, textViewCpuCores, textViewCpuTemperature;
    private Button buttonGenerateWallet, buttonRevealWif, buttonSaveWif;
    private Button buttonCheckBalance, buttonOpenWebWallet;
    private TextView textViewGeneratedAddress, textViewGeneratedWif, textViewWalletBalance;
    private LinearLayout walletKeyOutput;
    private YentenWalletKey generatedWallet;
    private YentenWalletStorage walletStorage;
    private final ExecutorService balanceExecutor = Executors.newSingleThreadExecutor();
    private boolean wifRevealed;
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

        pageMining = findViewById(R.id.pageMining);
        pageWallet = findViewById(R.id.pageWallet);
        pageSettings = findViewById(R.id.pageSettings);
        textViewScreenTitle = findViewById(R.id.textViewScreenTitle);
        buttonNavMining = findViewById(R.id.buttonNavMining);
        buttonNavWallet = findViewById(R.id.buttonNavWallet);
        buttonNavSettings = findViewById(R.id.buttonNavSettings);
        buttonNavMining.setOnClickListener(view -> showPage(0));
        buttonNavWallet.setOnClickListener(view -> showPage(1));
        buttonNavSettings.setOnClickListener(view -> showPage(2));
        showPage(PreferenceManager.getDefaultSharedPreferences(this).getInt("active_page", 0));

        editTextServer = findViewById(R.id.editTextServer);
        editTextUser = findViewById(R.id.editTextUser);
        walletInputLayout = findViewById(R.id.walletInputLayout);
        editTextUser.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                updateWalletInputHint(text);
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        updateWalletInputHint(editTextUser.getText());
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
        buttonGenerateWallet = findViewById(R.id.buttonGenerateWallet);
        buttonRevealWif = findViewById(R.id.buttonRevealWif);
        buttonSaveWif = findViewById(R.id.buttonSaveWif);
        buttonCheckBalance = findViewById(R.id.buttonCheckBalance);
        buttonOpenWebWallet = findViewById(R.id.buttonOpenWebWallet);
        textViewGeneratedAddress = findViewById(R.id.textViewGeneratedAddress);
        textViewGeneratedWif = findViewById(R.id.textViewGeneratedWif);
        textViewWalletBalance = findViewById(R.id.textViewWalletBalance);
        walletKeyOutput = findViewById(R.id.walletKeyOutput);
        walletStorage = new YentenWalletStorage(this);

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
                else if (position == 2) editTextServer.setText(RPLANT_EU_POOL);
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

        buttonGenerateWallet.setOnClickListener(view -> {
            if (generatedWallet == null) {
                generateWallet();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.replace_wallet_title)
                        .setMessage(R.string.replace_wallet_warning)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.generate_wallet, (dialog, which) -> generateWallet())
                        .show();
            }
        });
        buttonRevealWif.setOnClickListener(view -> {
            if (generatedWallet == null) return;
            wifRevealed = !wifRevealed;
            textViewGeneratedWif.setText(wifRevealed ? generatedWallet.wif : getString(R.string.wif_hidden));
            textViewGeneratedWif.setVisibility(View.VISIBLE);
            buttonRevealWif.setText(wifRevealed ? R.string.hide_wif : R.string.reveal_wif);
            if (wifRevealed) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        });
        buttonSaveWif.setOnClickListener(view -> confirmSaveWallet());
        buttonCheckBalance.setOnClickListener(view -> checkWalletBalance());
        buttonOpenWebWallet.setOnClickListener(view -> confirmOpenWebWallet());

        restoreWallet();

        buttonDrive.setOnClickListener(view -> toggleMining());
        changeState(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("mining_running", false));
        showDeviceInfo();
    }

    private void showPage(int page) {
        int selectedPage = Math.max(0, Math.min(page, 2));
        if (selectedPage != 1 && wifRevealed) hideWif();
        pageMining.setVisibility(selectedPage == 0 ? View.VISIBLE : View.GONE);
        pageWallet.setVisibility(selectedPage == 1 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(selectedPage == 2 ? View.VISIBLE : View.GONE);
        textViewScreenTitle.setText(selectedPage == 0 ? R.string.page_mining_title
                : (selectedPage == 1 ? R.string.page_wallet_title : R.string.page_settings_title));
        updateNavigationButton(buttonNavMining, selectedPage == 0);
        updateNavigationButton(buttonNavWallet, selectedPage == 1);
        updateNavigationButton(buttonNavSettings, selectedPage == 2);
        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt("active_page", selectedPage).apply();
    }

    private void updateWalletInputHint(CharSequence value) {
        boolean isEmpty = value == null || value.toString().trim().isEmpty();
        if (isEmpty) {
            walletInputLayout.setHelperTextEnabled(true);
            walletInputLayout.setHelperText(getString(R.string.wallet_empty_hint));
        } else {
            walletInputLayout.setHelperText(null);
            walletInputLayout.setHelperTextEnabled(false);
        }
    }

    private void updateNavigationButton(Button button, boolean selected) {
        button.setAlpha(selected ? 1.0f : 0.68f);
        button.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        button.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.colorPrimary : R.color.navigationInactive));
    }

    private void hideWif() {
        wifRevealed = false;
        textViewGeneratedWif.setText(getString(R.string.wif_hidden));
        buttonRevealWif.setText(R.string.reveal_wif);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
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
        if (wifRevealed) {
            hideWif();
        }
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

    private void generateWallet() {
        try {
            YentenWalletKey candidate = YentenWalletKey.generate(new java.security.SecureRandom());
            walletStorage.save(candidate);
            showWallet(candidate);
            Toast.makeText(this, R.string.wallet_generated, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Unable to generate Yenten wallet", e);
            Toast.makeText(this, R.string.wallet_generation_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void restoreWallet() {
        try {
            YentenWalletKey wallet = walletStorage.load();
            if (wallet != null) showWallet(wallet);
        } catch (Exception e) {
            Log.e(TAG, "Unable to unlock saved Yenten wallet", e);
            Toast.makeText(this, R.string.wallet_storage_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void showWallet(YentenWalletKey wallet) {
        generatedWallet = wallet;
        wifRevealed = false;
        textViewGeneratedAddress.setText(wallet.address);
        textViewGeneratedWif.setText(getString(R.string.wif_hidden));
        textViewGeneratedWif.setVisibility(View.VISIBLE);
        buttonRevealWif.setText(R.string.reveal_wif);
        walletKeyOutput.setVisibility(View.VISIBLE);
        editTextUser.setText(wallet.address);
        storeSetting();
    }

    private void confirmSaveWallet() {
        if (generatedWallet == null) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.save_wif)
                .setMessage(R.string.save_wif_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.continue_saving, (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TITLE, "Yenten-wallet.txt");
                    startActivityForResult(intent, REQUEST_SAVE_WALLET);
                })
                .show();
    }

    private void checkWalletBalance() {
        if (generatedWallet == null) return;
        String address = generatedWallet.address;
        buttonCheckBalance.setEnabled(false);
        textViewWalletBalance.setText(R.string.balance_loading);
        balanceExecutor.execute(() -> {
            try {
                YentenBalanceClient.Balance balance = YentenBalanceClient.fetch(address);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    textViewWalletBalance.setText(getString(R.string.balance_result,
                            balance.balance, balance.received));
                    buttonCheckBalance.setEnabled(true);
                });
            } catch (Exception e) {
                Log.w(TAG, "Unable to fetch Yenten address balance", e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    textViewWalletBalance.setText(R.string.balance_unavailable);
                    buttonCheckBalance.setEnabled(true);
                });
            }
        });
    }

    private void confirmOpenWebWallet() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.web_wallet_warning_title)
                .setMessage(R.string.web_wallet_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.open_web_wallet, (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://wallet.yentencoin.info/"));
                    startActivity(intent);
                })
                .show();
    }

    @Override protected void onDestroy() {
        balanceExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_WALLET || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (generatedWallet == null) {
            try { generatedWallet = walletStorage.load(); }
            catch (Exception e) { Log.e(TAG, "Unable to load wallet for export", e); }
        }
        if (uri == null || generatedWallet == null) {
            Toast.makeText(this, R.string.wallet_storage_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        String contents = getString(R.string.wallet_backup_contents, generatedWallet.address, generatedWallet.wif);
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new java.io.IOException("Unable to open selected file");
            output.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Toast.makeText(this, R.string.wallet_saved, Toast.LENGTH_LONG).show();
        } catch (java.io.IOException e) {
            Log.e(TAG, "Unable to save wallet backup", e);
            Toast.makeText(this, R.string.wallet_save_failed, Toast.LENGTH_LONG).show();
        }
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
        int pool = server.contains("yenten-pool.info") ? 0
                : (server.contains("cpu-pool.com") ? 1
                : (server.contains("eu.rplant.xyz") ? 2 : 3));
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
