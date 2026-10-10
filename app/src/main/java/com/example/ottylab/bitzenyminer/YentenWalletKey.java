package com.example.ottylab.bitzenyminer;

import org.spongycastle.asn1.sec.SECNamedCurves;
import org.spongycastle.crypto.digests.RIPEMD160Digest;
import org.spongycastle.crypto.params.ECDomainParameters;
import org.spongycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Creates legacy, compressed mainnet keys compatible with Yenten Core. */
final class YentenWalletKey {
    private static final int ADDRESS_PREFIX = 78;
    private static final int PRIVATE_KEY_PREFIX = 123;
    private static final char[] BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    final String address;
    final String wif;

    private YentenWalletKey(String address, String wif) {
        this.address = address;
        this.wif = wif;
    }

    static YentenWalletKey generate(SecureRandom random) throws Exception {
        org.spongycastle.asn1.x9.X9ECParameters curve = SECNamedCurves.getByName("secp256k1");
        if (curve == null) throw new IllegalStateException("secp256k1 is unavailable");
        ECDomainParameters domain = new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());

        byte[] secretBytes = new byte[32];
        BigInteger secret;
        do {
            random.nextBytes(secretBytes);
            secret = new BigInteger(1, secretBytes);
        } while (secret.signum() == 0 || secret.compareTo(domain.getN()) >= 0);

        try {
            ECPoint publicKey = domain.getG().multiply(secret).normalize();
            byte[] publicKeyBytes = publicKey.getEncoded(true);
            byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes);
            RIPEMD160Digest ripemd160 = new RIPEMD160Digest();
            byte[] publicKeyHash = new byte[ripemd160.getDigestSize()];
            ripemd160.update(sha256, 0, sha256.length);
            ripemd160.doFinal(publicKeyHash, 0);

            byte[] addressPayload = new byte[1 + publicKeyHash.length];
            addressPayload[0] = (byte) ADDRESS_PREFIX;
            System.arraycopy(publicKeyHash, 0, addressPayload, 1, publicKeyHash.length);

            byte[] wifPayload = new byte[1 + secretBytes.length + 1];
            wifPayload[0] = (byte) PRIVATE_KEY_PREFIX;
            System.arraycopy(secretBytes, 0, wifPayload, 1, secretBytes.length);
            wifPayload[wifPayload.length - 1] = 1; // compressed public key marker

            return new YentenWalletKey(base58Check(addressPayload), base58Check(wifPayload));
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }

    private static String base58Check(byte[] payload) throws Exception {
        byte[] first = MessageDigest.getInstance("SHA-256").digest(payload);
        byte[] second = MessageDigest.getInstance("SHA-256").digest(first);
        byte[] checked = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(second, 0, checked, payload.length, 4);

        int zeros = 0;
        while (zeros < checked.length && checked[zeros] == 0) zeros++;
        BigInteger value = new BigInteger(1, checked);
        StringBuilder encoded = new StringBuilder();
        BigInteger radix = BigInteger.valueOf(58);
        while (value.signum() > 0) {
            BigInteger[] quotientAndRemainder = value.divideAndRemainder(radix);
            encoded.append(BASE58[quotientAndRemainder[1].intValue()]);
            value = quotientAndRemainder[0];
        }
        for (int i = 0; i < zeros; i++) encoded.append(BASE58[0]);
        return encoded.reverse().toString();
    }
}
