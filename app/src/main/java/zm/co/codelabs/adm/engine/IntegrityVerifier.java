package zm.co.codelabs.adm.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class IntegrityVerifier {
    public boolean sizeMatches(File file, long expected) { return expected < 0 || file.length() == expected; }
    public boolean checksumMatches(File file, String algorithm, String expectedHex) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[256 * 1024];
            try (FileInputStream in = new FileInputStream(file)) { int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n); }
            byte[] bytes = digest.digest(); StringBuilder hex = new StringBuilder(bytes.length * 2); for (byte value : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString().equalsIgnoreCase(expectedHex);
        } catch (NoSuchAlgorithmException e) { throw new IOException("Unsupported checksum algorithm", e); }
    }
}
