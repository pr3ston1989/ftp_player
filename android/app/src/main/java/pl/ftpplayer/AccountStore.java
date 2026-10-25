package pl.ftpplayer;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Persist passwords encrypted by a non-exportable Android Keystore key. */
final class AccountStore {
    private final Context context;
    AccountStore(Context context) { this.context = context; }

    private javax.crypto.SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "ftp_player_accounts_v1";
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey(alias, null);
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")), Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split(":", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8");
    }

    JSONArray load() throws Exception {
        JSONArray stored = new JSONArray(context.getSharedPreferences("accounts", 0).getString("profiles", "[]"));
        for (int i=0; i<stored.length(); i++) {
            JSONObject row = stored.getJSONObject(i);
            row.put("password", decrypt(row.getString("secret"))); row.remove("secret");
        }
        return stored;
    }

    void save(JSONArray accounts) throws Exception {
        JSONArray stored = new JSONArray(accounts.toString());
        for (int i=0; i<stored.length(); i++) {
            JSONObject row = stored.getJSONObject(i);
            row.put("secret", encrypt(row.optString("password"))); row.remove("password");
        }
        if (!context.getSharedPreferences("accounts", 0).edit().putString("profiles", stored.toString()).commit())
            throw new java.io.IOException("Nie udało się zapisać konta");
    }
}
