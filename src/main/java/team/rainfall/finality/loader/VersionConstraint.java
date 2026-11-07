package team.rainfall.finality.loader;

import java.util.ArrayList;
import java.util.List;

public final class VersionConstraint {
    private final List<Clause> clauses;
    private VersionConstraint(List<Clause> clauses) { this.clauses = clauses; }
    public static VersionConstraint parse(String value) throws SemVer.IOExceptionVersion {
        List<Clause> clauses = new ArrayList<Clause>();
        for (String token : value.trim().split("[\\s,]+")) {
            if (token.isEmpty()) continue;
            String operator = "=";
            String version = token;
            for (String candidate : new String[]{">=", "<=", ">", "<", "="}) {
                if (token.startsWith(candidate)) { operator = candidate; version = token.substring(candidate.length()); break; }
            }
            clauses.add(new Clause(operator, SemVer.parse(version)));
        }
        if (clauses.isEmpty()) throw new SemVer.IOExceptionVersion("Invalid version range: " + value);
        return new VersionConstraint(clauses);
    }
    public boolean matches(SemVer actual) { for (Clause clause : clauses) if (!clause.matches(actual)) return false; return true; }
    @Override public String toString() { StringBuilder result = new StringBuilder(); for (Clause clause : clauses) { if (result.length() > 0) result.append(' '); result.append(clause.operator).append(clause.version); } return result.toString(); }

    private static final class Clause {
        final String operator; final SemVer version;
        Clause(String operator, SemVer version) { this.operator = operator; this.version = version; }
        boolean matches(SemVer actual) { int result = actual.compareTo(version); if (">=".equals(operator)) return result >= 0; if ("<=".equals(operator)) return result <= 0; if (">".equals(operator)) return result > 0; if ("<".equals(operator)) return result < 0; return result == 0; }
    }
}
