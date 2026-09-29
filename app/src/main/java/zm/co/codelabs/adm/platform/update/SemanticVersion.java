package zm.co.codelabs.adm.platform.update;

public final class SemanticVersion implements Comparable<SemanticVersion> {
    private final int major;
    private final int minor;
    private final int patch;

    private SemanticVersion(int major, int minor, int patch) {
        this.major = major; this.minor = minor; this.patch = patch;
    }

    public static SemanticVersion parse(String value) {
        if (value == null) throw new IllegalArgumentException("Missing version");
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);
        String[] parts = normalized.split("\\.", -1);
        if (parts.length < 2 || parts.length > 3) throw new IllegalArgumentException("Invalid release version");
        try {
            int major = component(parts[0]), minor = component(parts[1]);
            int patch = parts.length == 3 ? component(parts[2]) : 0;
            return new SemanticVersion(major, minor, patch);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid release version", e); }
    }

    private static int component(String value) {
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) throw new NumberFormatException();
        return Integer.parseInt(value);
    }

    @Override public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result == 0) result = Integer.compare(minor, other.minor);
        return result == 0 ? Integer.compare(patch, other.patch) : result;
    }

    @Override public String toString() { return major + "." + minor + "." + patch; }
}
