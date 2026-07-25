package com.codescope;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stateless helper for resolving a user-supplied method selector into
 * either a project-declared {@link MethodKey} or a set of recorded
 * call-edge keys (used for library-only methods). Two
 * {@code *Service} classes share this logic via the {@link Result}
 * sealed type.
 */
public final class MethodResolver {

    private MethodResolver() {}

    /**
     * The outcome of resolving a user selector. Pattern-matched by
     * callers via {@code instanceof}.
     */
    public sealed interface Result
            permits Result.ProjectTarget, Result.LibrarySeeds {

        /** Project-resolved target; dispatch on it directly. */
        record ProjectTarget(MethodKey target) implements Result {}

        /**
         * Target was not declared in the project. {@code seeds} are the
         * recorded call-edge keys (each a {@code MethodKey}). When the
         * index has no edges for the user's selector, {@code seeds}
         * contains a single synthesized {@link MethodKey} so the
         * downstream analyzer still produces a coherent "no callers"
         * answer.
         */
        record LibrarySeeds(List<MethodKey> seeds, MethodKey display) implements Result {}
    }

    /**
     * Resolve a user-supplied selector. Either returns the project's
     * MethodKey, or returns the recorded call-edge keys (synthesizing
     * a display key from the user's selector if no edges exist).
     * Ambiguity is propagated via {@link ProjectIndex.AmbiguousMethodException}
     * so the caller can wrap it in its own exception type with a hint.
     */
    public static Result resolve(ProjectIndex index,
                                 String className, String methodName,
                                 Integer arity, List<String> paramTypes)
            throws ProjectIndex.AmbiguousMethodException {
        MethodKey target = index.resolveTarget(className, methodName, arity, paramTypes);
        if (target != null) return new Result.ProjectTarget(target);

        List<MethodKey> seeds = index.findInvokedKeys(className, methodName, arity, paramTypes);
        MethodKey display = new MethodKey(className, methodName,
                arity == null ? 0 : arity,
                paramTypes == null ? List.of() : paramTypes);
        if (seeds.isEmpty()) seeds = List.of(display);
        return new Result.LibrarySeeds(seeds, display);
    }

    /**
     * Build the user-facing hint shown after an
     * {@link ProjectIndex.AmbiguousMethodException}. Two branches:
     * project overloads are visible (list them), or the class is
     * library-only (advise passing {@code paramTypes}).
     */
    public static String overloadsHint(ProjectIndex index,
                                       String className, String methodName,
                                       ProjectIndex.AmbiguousMethodException cause) {
        List<MethodKey> projectOverloads = index.findOverloads(className, methodName);
        if (projectOverloads.isEmpty()) {
            return cause.getMessage() + " no overloads are visible in this project's sources "
                    + "(the class is likely from a library); pass `paramTypes` with the FQN types "
                    + "to pick one.";
        }
        return cause.getMessage() + " available overloads: "
                + projectOverloads.stream()
                        .map(MethodKey::toString)
                        .collect(Collectors.toList()) + ".";
    }

    /**
     * Append the "(combined callers across N library overloads: [...])"
     * suffix used by {@code trace_callers} and {@code find_call_sites}
     * when the library-target resolution bundled multiple overloads into
     * one display key. Returns the empty string when no union happened
     * (single seed or empty seeds list).
     */
    public static String overloadUnionSuffix(List<MethodKey> seeds) {
        if (seeds.size() <= 1) return "";
        String overloads = seeds.stream()
                .map(MethodKey::fullSignature)
                .sorted()
                .collect(Collectors.toList())
                .toString();
        return " (combined callers across " + seeds.size()
                + " library overloads: " + overloads + ")";
    }
}
