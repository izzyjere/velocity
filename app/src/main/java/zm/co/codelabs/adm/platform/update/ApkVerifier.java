package zm.co.codelabs.adm.platform.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public final class ApkVerifier {
    private ApkVerifier() { }

    @SuppressWarnings("deprecation")
    public static void verify(Context context, File apk, String expectedVersion) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getCanonicalPath(), flags);
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)) throw new SecurityException("Update package identity does not match");
        if (candidate.versionName == null || SemanticVersion.parse(candidate.versionName).compareTo(SemanticVersion.parse(expectedVersion)) != 0) throw new SecurityException("Update version does not match release metadata");
        long installedCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        long candidateCode = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
        if (candidateCode <= installedCode) throw new SecurityException("Update version is not newer than the installed app");
        if (Build.VERSION.SDK_INT >= 28) {
            if (installed.signingInfo == null || candidate.signingInfo == null) throw new SecurityException("Update signing information is missing");
            Signature[] installedCurrent = installed.signingInfo.getApkContentsSigners();
            Signature[] candidateCurrent = candidate.signingInfo.getApkContentsSigners();
            if (installed.signingInfo.hasMultipleSigners() || candidate.signingInfo.hasMultipleSigners()) {
                if (!fingerprints(installedCurrent).equals(fingerprints(candidateCurrent))) throw new SecurityException("Update signer does not match");
            } else {
                Set<String> candidateHistory = fingerprints(candidate.signingInfo.getSigningCertificateHistory());
                for (String signer : fingerprints(installedCurrent)) if (!candidateHistory.contains(signer)) throw new SecurityException("Update signer does not match");
            }
        } else if (!fingerprints(installed.signatures).equals(fingerprints(candidate.signatures))) {
            throw new SecurityException("Update signer does not match");
        }
    }

    private static Set<String> fingerprints(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) throw new SecurityException("Signing certificate is missing");
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); Set<String> values = new HashSet<>();
        for (Signature signature : signatures) {
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest.digest(signature.toByteArray())) value.append(String.format(java.util.Locale.ROOT, "%02x", part));
            values.add(value.toString());
        }
        return values;
    }
}
