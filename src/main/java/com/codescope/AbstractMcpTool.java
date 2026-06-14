package com.codescope;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base class for every MCP {@link Tool} that takes a {@code project}
 * argument plus the same handful of optional scalar/array params.
 * Centralizes the argument-parsing helpers
 * ({@link #requiredString}, {@link #optionalInt},
 * {@link #optionalStringList}, {@link #optionalBool}) and the
 * {@code hostDefaultProject} fallback so concrete tools shrink to
 * their own schema + invoke body.
 *
 * <p>Kept as a base class (not an interface with default methods) so
 * the {@code hostDefaultProject} field can be {@code volatile} and
 * settable from the host, which an interface field cannot model.
 */
abstract class AbstractMcpTool implements Tool {

    /**
     * Supplied by the host via MCP {@code roots}; used when the tool
     * call omits {@code project}. {@code volatile} because the host
     * updates it on a different thread than the tool invocation
     * thread.
     */
    protected volatile String hostDefaultProject;

    public void setHostDefaultProject(String path) { this.hostDefaultProject = path; }

    static String requiredString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || !(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Missing or non-string required argument: " + key);
        }
        return s;
    }

    static Integer optionalInt(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        int n;
        if (v instanceof Number num) {
            n = num.intValue();
        } else if (v instanceof String s) {
            try {
                n = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Optional argument '" + key
                        + "' must be an integer; got: " + v);
            }
        } else {
            throw new IllegalArgumentException("Optional argument '" + key
                    + "' must be an integer; got: " + v);
        }
        if (n < 0) {
            throw new IllegalArgumentException("Optional argument '" + key
                    + "' must be >= 0; got: " + n);
        }
        return n;
    }

    static List<String> optionalStringList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object item : list) {
                if (!(item instanceof String s)) {
                    throw new IllegalArgumentException("Optional argument '" + key
                            + "' must be a list of strings; got: " + item);
                }
                out.add(s);
            }
            return out;
        }
        throw new IllegalArgumentException("Optional argument '" + key
                + "' must be a list; got: " + v);
    }

    static boolean optionalBool(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        throw new IllegalArgumentException("Optional argument '" + key
                + "' must be a boolean; got: " + v);
    }

    /**
     * Optional string that treats a blank value the same as absent
     * (returns null). For required strings use {@link #requiredString}.
     */
    static String optionalString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new IllegalArgumentException("Optional argument '" + key
                    + "' must be a string; got: " + v);
        }
        if (s.isBlank()) return null;
        return s;
    }

    /**
     * Resolve the project root from the {@code project} argument or,
     * if absent, from the host-advertised default (set via
     * {@link #setHostDefaultProject}). Deliberately does NOT fall back
     * to {@code System.getProperty("user.dir")} or the process CWD —
     * those are set by the MCP host at launch time, not by the user,
     * and guessing wrong is worse than asking for an explicit path.
     */
    protected Path resolveProjectRoot(Map<String, Object> args) {
        Object p = args.get("project");
        if (p != null) return Paths.get(p.toString()).toAbsolutePath();
        if (hostDefaultProject != null && !hostDefaultProject.isBlank()) {
            return Paths.get(hostDefaultProject).toAbsolutePath();
        }
        throw new IllegalArgumentException(
                "No `project` argument and no default project root advertised by the host. "
                        + "Pass `project` with an absolute path to a Maven project root, or have "
                        + "the host advertise the workspace root via MCP `roots`.");
    }
}
