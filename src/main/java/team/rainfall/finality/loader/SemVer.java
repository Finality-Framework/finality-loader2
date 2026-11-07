package team.rainfall.finality.loader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.BigInteger;

public final class SemVer implements Comparable<SemVer> {
    private static final Pattern FORMAT = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+([0-9A-Za-z.-]+))?$");
    private final BigInteger major, minor, patch;
    private final String pre, build;

    private SemVer(BigInteger major, BigInteger minor, BigInteger patch, String pre, String build) { this.major = major; this.minor = minor; this.patch = patch; this.pre = pre; this.build = build; }
    public static SemVer parse(String value) throws IOExceptionVersion {
        Matcher match = FORMAT.matcher(value == null ? "" : value.trim());
        if (!match.matches() || !validIdentifiers(match.group(4), true) || !validIdentifiers(match.group(5), false)) throw new IOExceptionVersion("Invalid SemVer: " + value);
        return new SemVer(new BigInteger(match.group(1)), new BigInteger(match.group(2)), new BigInteger(match.group(3)), match.group(4), match.group(5));
    }
    private static boolean validIdentifiers(String identifiers, boolean prerelease) {
        if (identifiers == null) return true;
        for (String identifier : identifiers.split("\\.", -1)) {
            if (identifier.isEmpty()) return false;
            if (prerelease && identifier.matches("\\d+") && identifier.length() > 1 && identifier.startsWith("0")) return false;
        }
        return true;
    }
    @Override public int compareTo(SemVer other) { int result = major.compareTo(other.major); if (result != 0) return result; result = minor.compareTo(other.minor); if (result != 0) return result; result = patch.compareTo(other.patch); if (result != 0) return result; if (pre == null && other.pre == null) return 0; if (pre == null) return 1; if (other.pre == null) return -1; return comparePre(pre, other.pre); }
    private static int comparePre(String left, String right) { String[] a = left.split("\\."), b = right.split("\\."); for (int i = 0; i < Math.max(a.length, b.length); i++) { if (i == a.length) return -1; if (i == b.length) return 1; boolean an = a[i].matches("\\d+"), bn = b[i].matches("\\d+"); if (an && bn) { int result = new BigInteger(a[i]).compareTo(new BigInteger(b[i])); if (result != 0) return result; } else if (an != bn) return an ? -1 : 1; else { int result = a[i].compareTo(b[i]); if (result != 0) return result; } } return 0; }
    @Override public String toString() { return major + "." + minor + "." + patch + (pre == null ? "" : "-" + pre) + (build == null ? "" : "+" + build); }
    public static final class IOExceptionVersion extends Exception { public IOExceptionVersion(String message) { super(message); } }
}
