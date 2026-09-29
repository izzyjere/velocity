package zm.co.codelabs.adm.platform.update;

public final class UpdateInfo {
    public final String version;
    public final String apkUrl;
    public final String checksumUrl;
    public final String digest;
    public final String releaseUrl;
    public final String notes;
    public final long size;

    UpdateInfo(String version, String apkUrl, String checksumUrl, String digest, String releaseUrl, String notes, long size) {
        this.version = version; this.apkUrl = apkUrl; this.checksumUrl = checksumUrl; this.digest = digest;
        this.releaseUrl = releaseUrl; this.notes = notes; this.size = size;
    }
}
