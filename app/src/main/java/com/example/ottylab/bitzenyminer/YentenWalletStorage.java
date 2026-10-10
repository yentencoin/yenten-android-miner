package com.example.ottylab.bitzenyminer;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/** Encrypts the wallet record with AES-GCM and wraps its data key using Android Keystore RSA. */
final class YentenWalletStorage {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "yenten_wallet_wrap_key_v1";
    private static final String PREFS = "wallet_secure_store";
    private static final String RECORD = "encrypted_wallet_v1";
    private static final int IV_LENGTH = 12;

    private final Context context;
    private final SharedPreferences preferences;

    YentenWalletStorage(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void save(YentenWalletKey wallet) throws Exception {
        byte[] dataKey = new byte[32];
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(dataKey);
        random.nextBytes(iv);
        byte[] plaintext = (wallet.address + "\n" + wallet.wif).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] encryptedData = aes.doFinal(plaintext);
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey().getPublic());
            byte[] wrappedKey = rsa.doFinal(dataKey);

            String value = "1." + encode(wrappedKey) + "." + encode(iv) + "." + encode(encryptedData);
            if (!preferences.edit().putString(RECORD, value).commit()) {
                throw new IOException("Unable to commit encrypted wallet storage");
            }
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    YentenWalletKey load() throws Exception {
        String value = preferences.getString(RECORD, null);
        if (value == null) return null;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4 || !"1".equals(parts[0])) throw new IOException("Invalid wallet record");

        byte[] wrappedKey = decode(parts[1]);
        byte[] iv = decode(parts[2]);
        byte[] encryptedData = decode(parts[3]);
        byte[] dataKey = null;
        byte[] plaintext = null;
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            java.security.Key privateKey = keyStore.getKey(KEY_ALIAS, null);
            if (privateKey == null) throw new IOException("Wallet encryption key is missing");

            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, privateKey);
            dataKey = rsa.doFinal(wrappedKey);

            Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(128, iv));
            plaintext = aes.doFinal(encryptedData);
            String[] walletParts = new String(plaintext, StandardCharsets.UTF_8).split("\\n", -1);
            if (walletParts.length != 2 || walletParts[0].isEmpty() || walletParts[1].isEmpty()) {
                throw new IOException("Invalid decrypted wallet record");
            }
            return new YentenWalletKey(walletParts[0], walletParts[1]);
        } finally {
            if (dataKey != null) Arrays.fill(dataKey, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(wrappedKey, (byte) 0);
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(encryptedData, (byte) 0);
        }
    }

    private java.security.KeyPair getOrCreateWrappingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return new java.security.KeyPair(keyStore.getCertificate(KEY_ALIAS).getPublicKey(),
                    (java.security.PrivateKey) keyStore.getKey(KEY_ALIAS, null));
        }

        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 30);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", KEYSTORE);
        generator.initialize(new KeyPairGeneratorSpec.Builder(context)
                .setAlias(KEY_ALIAS)
                .setSubject(new X500Principal("CN=Yenten Miner Wallet Storage"))
                .setSerialNumber(BigInteger.ONE)
                .setStartDate(start.getTime())
                .setEndDate(end.getTime())
                .setKeySize(2048)
                .build());
        return generator.generateKeyPair();
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }
}
