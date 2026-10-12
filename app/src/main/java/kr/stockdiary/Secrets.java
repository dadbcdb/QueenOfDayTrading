package kr.stockdiary;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Secrets {
    private final Context context;
    private static final String ALIAS="stockdiary_credentials_v1";
    Secrets(Context c) { context=c; }
    private javax.crypto.SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); g.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey(ALIAS,null);
    }
    void save(String appKey, String secret, boolean mock) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=c.doFinal(new JSONObject().put("key",appKey).put("secret",secret).put("mock",mock).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(!context.getSharedPreferences("vault",0).edit().putString("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP))
            .putString("data",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()) throw new java.io.IOException("인증정보 저장 실패");
    }
    JSONObject load() throws Exception {
        android.content.SharedPreferences p=context.getSharedPreferences("vault",0);
        if(!p.contains("data")) return null;
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
        return new JSONObject(new String(c.doFinal(Base64.decode(p.getString("data",""),Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8));
    }
    boolean clear() { return context.getSharedPreferences("vault",0).edit().clear().commit(); }
}
