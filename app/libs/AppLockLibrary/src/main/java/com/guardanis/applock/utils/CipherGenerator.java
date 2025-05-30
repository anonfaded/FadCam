package com.guardanis.applock.utils;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;

import com.fadcam.R;

import java.security.KeyStore;
import java.security.UnrecoverableKeyException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;

import androidx.annotation.RequiresApi;

@RequiresApi(api = Build.VERSION_CODES.M)
public class CipherGenerator {

    private static final String KEYSTORE_NAME  = "AndroidKeyStore";

    public Cipher generateAuthCipher(Context context, boolean forceRegenerate, int attempts) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            throw new RuntimeException("generateAuthCipher() not supported before Api 23");

        String alias = context.getString(R.string.applock__fingerprint_alias);

        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_NAME);
        keyStore.load(null);

        if (forceRegenerate || !keyStore.containsAlias(alias)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_NAME);
            keyGenerator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());

            keyGenerator.generateKey();
        }

        String cipherFormat = String.format(
                "%s/%s/%s",
                KeyProperties.KEY_ALGORITHM_AES,
                KeyProperties.BLOCK_MODE_CBC,
                KeyProperties.ENCRYPTION_PADDING_PKCS7);

        try {
            Cipher cipher = Cipher.getInstance(cipherFormat);
            cipher.init(Cipher.ENCRYPT_MODE, keyStore.getKey(alias, null));

            return cipher;
        }
        catch (KeyPermanentlyInvalidatedException e) {
            e.printStackTrace();

            if (1 < attempts)
                return generateAuthCipher(context, true, attempts + 1);
        }
        catch (UnrecoverableKeyException e) {
            e.printStackTrace();

            if (1 < attempts)
                return generateAuthCipher(context, true, attempts + 1);
        }

        return null;
    }
}
