package com.bikegps.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Persists the opaque backend session encrypted by a non-exportable Android Keystore key. */
public final class SecureSessionStore {
  private static final String KEY_ALIAS = "bikegps_backend_session_v1";
  private static final String PREFS = "bikegps_secure";
  private static final String SESSION = "session";
  private final SharedPreferences preferences;

  public SecureSessionStore(Context context) {
    preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  public void save(String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
    byte[] iv = cipher.getIV();
    ByteBuffer packed = ByteBuffer.allocate(1 + iv.length + encrypted.length);
    packed.put((byte) iv.length).put(iv).put(encrypted);
    preferences.edit().putString(SESSION, Base64.encodeToString(packed.array(), Base64.NO_WRAP)).apply();
  }

  public String load() {
    String encoded = preferences.getString(SESSION, null);
    if (encoded == null) return null;
    try {
      ByteBuffer packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
      int ivLength = packed.get() & 0xff;
      if (ivLength < 12 || ivLength > 16 || packed.remaining() <= ivLength) throw new IllegalStateException();
      byte[] iv = new byte[ivLength];
      packed.get(iv);
      byte[] encrypted = new byte[packed.remaining()];
      packed.get(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (Exception invalid) {
      clear();
      return null;
    }
  }

  public void clear() { preferences.edit().remove(SESSION).apply(); }

  private static SecretKey key() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    java.security.Key existing = store.getKey(KEY_ALIAS, null);
    if (existing instanceof SecretKey) return (SecretKey) existing;
    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .build());
    return generator.generateKey();
  }
}
