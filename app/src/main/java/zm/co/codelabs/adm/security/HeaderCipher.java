package zm.co.codelabs.adm.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class HeaderCipher {
    private static final String ALIAS = "velocity_request_headers_v1";
    public byte[] encrypt(Map<String, String> headers) throws IOException {
        if (headers == null || headers.isEmpty()) return null;
        try {
            ByteArrayOutputStream plainBytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(plainBytes)) { out.writeInt(headers.size()); for (Map.Entry<String, String> entry : headers.entrySet()) { out.writeUTF(entry.getKey()); out.writeUTF(entry.getValue()); } }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); byte[] ciphertext = cipher.doFinal(plainBytes.toByteArray());
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(result)) { out.writeInt(cipher.getIV().length); out.write(cipher.getIV()); out.write(ciphertext); }
            return result.toByteArray();
        } catch (Exception e) { throw new IOException("Unable to protect request headers", e); }
    }
    public Map<String, String> decrypt(byte[] encrypted) throws IOException {
        if (encrypted == null || encrypted.length == 0) return Map.of();
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encrypted)); int ivLength = input.readInt();
            if (ivLength < 12 || ivLength > 32) throw new IOException("Invalid encrypted header payload");
            byte[] iv = new byte[ivLength]; input.readFully(iv); byte[] ciphertext = new byte[input.available()]; input.readFully(ciphertext); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            DataInputStream plain = new DataInputStream(new ByteArrayInputStream(cipher.doFinal(ciphertext))); int size = plain.readInt();
            if (size < 0 || size > 64) throw new IOException("Invalid header count"); Map<String, String> result = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) result.put(plain.readUTF(), plain.readUTF()); return Map.copyOf(result);
        } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException("Unable to read protected request headers", e); }
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        KeyStore.Entry existing = store.getEntry(ALIAS, null); if (existing instanceof KeyStore.SecretKeyEntry entry) return entry.getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
}
