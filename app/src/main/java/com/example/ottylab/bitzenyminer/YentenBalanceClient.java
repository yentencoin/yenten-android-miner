package com.example.ottylab.bitzenyminer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class YentenBalanceClient {
    private static final String API_BASE = "https://api.yentencoin.info/balance/";
    private static final long ATOMIC_UNITS_PER_YTN = 100_000_000L;

    static final class Balance {
        final String balance;
        final String received;

        Balance(String balance, String received) {
            this.balance = balance;
            this.received = received;
        }
    }

    private YentenBalanceClient() { }

    static Balance fetch(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + address).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new java.io.IOException("Balance API HTTP " + status);
            byte[] response = readLimited(connection.getInputStream(), 64 * 1024);
            JSONObject root = new JSONObject(new String(response, StandardCharsets.UTF_8));
            if (!root.isNull("error")) throw new java.io.IOException("Balance API returned an error");
            JSONObject result = root.optJSONObject("result");
            if (result == null) throw new java.io.IOException("Balance API response is missing result");
            return new Balance(formatCoins(result.get("balance")), formatCoins(result.get("received")));
        } finally {
            connection.disconnect();
        }
    }

    private static String formatCoins(Object atomicValue) throws Exception {
        BigDecimal atomic = new BigDecimal(String.valueOf(atomicValue));
        return atomic.divide(BigDecimal.valueOf(ATOMIC_UNITS_PER_YTN), 8, BigDecimal.ROUND_UNNECESSARY)
                .toPlainString();
    }

    private static byte[] readLimited(InputStream input, int maximumBytes) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = stream.read(buffer)) != -1) {
                total += count;
                if (total > maximumBytes) throw new java.io.IOException("Balance API response is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
