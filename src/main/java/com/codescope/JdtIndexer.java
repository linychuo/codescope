package com.codescope;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImplicitTypeDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Walks Java source files and produces a {@link ProjectIndex}. */
public final class JdtIndexer {

    /**
     * @param sources   absolute paths to .java files
     * @param classpath absolute paths to jars / class dirs passed to ASTParser
     * @param sourcepath absolute paths to source roots (empty is fine)
     * @param projectRoot absolute path of project root, used to relativize file paths
     */
    public ProjectIndex build(List<Path> sources,
                              List<String> classpath,
                              List<String> sourcepath,
                              Path projectRoot) {
        ProjectIndex index = new ProjectIndex();

        if (sources.isEmpty()) return index;

        String[] cp = classpath.toArray(new String[0]);
        String[] sp = sourcepath.toArray(new String[0]);
        // Match the read charset (UTF-8) above. Passing null falls back to
        // the JVM default charset, which on a non-UTF-8 host (Windows CI,
        // legacy GBK locales) makes JDT re-decode sourcepath reads with a
        // different charset than the reader, corrupting identifiers and
        // silently dropping call edges. The encoding names array must have
        // the same length as the sourcepath array — JDT rejects mismatches
        // — so fill per-entry, leaving an empty array when sourcepath is
        // empty (the main file's encoding comes from setSource's char[],
        // not from this array).
        String[] encodingNames = new String[sp.length];
        java.util.Arrays.fill(encodingNames, "UTF-8");

        // Java 21 virtual thread per file: parsing is mostly CPU (AST build)
        // but each task also does file I/O (readString) and JDT binding
        // resolution, which can block on classpath jars. Virtual threads let
        // us spawn one per file without capping concurrency at available
        // cores, and the executor's close() blocks until all complete.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(sources.size());
            for (Path src : sources) {
                futures.add(pool.submit(() -> parseFile(src, cp, sp, encodingNames, projectRoot, index)));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    // parseFile catches its own RuntimeExceptions and records
                    // them via recordSkippedFile. If a real Error (OOM, etc.)
                    // escapes, let it propagate — silently swallowing it would
                    // mask a memory issue as a missing-file problem.
                    if (cause instanceof Error err) throw err;
                    // Otherwise assume the parseFile path already recorded it.
                }
            }
        }
        // Reverse-type-hierarchy pass: every virtual-dispatch method
        // declared on a type T is also method-hierarchy-related to
        // any same-named, same-arity method declared on a subtype of
        // T. The forward recordMethodHierarchy walk (driven by
        // JDT's binding-side supertype traversal) misses some edges
        // — most notably the "interface extends abstract class"
        // case, where the implementing interface's
        // binding.getInterfaces() returns empty even though the AST
        // exposes the abstract supertype as a superInterfaceType.
        // By the time we get here, typeHierarchy is fully populated
        // from the AST walk, so we can repair missing edges
        // deterministically: for each declared method M, find all
        // subtypes of M.declaringClass and look for a same-named,
        // same-arity method on each. The gate uses the per-method
        // modifier bitmask recorded at declaration time so we never
        // link private or static methods (which are not virtual
        // dispatch — see ProjectIndex.recordMethodDeclarationModifiers).
        repairMethodHierarchyViaTypeHierarchy(index);
        return index;
    }

    /**
     * One-shot repair pass that adds method-hierarchy edges the
     * forward {@link JdtIndexerVisitor#recordMethodHierarchy} walk
     * missed. For every declared method M, walks
     * {@code typeHierarchy} downward from {@code M.declaringClass}
     * and links M to any same-named, same-arity method declared on a
     * subtype. Catches the
     * {@code interface IFoo extends AbsBase}-style edges that JDT
     * binding traversal does not expose but AST traversal does.
     *
     * <p>Gate: both M and the candidate must have a recorded
     * modifier bitmask and neither may be private or static.
     * {@link org.eclipse.jdt.core.dom.Modifier#isPrivate(int)} and
     * {@link org.eclipse.jdt.core.dom.Modifier#isStatic(int)} are
     * the JDT-side checks; private methods are lexically scoped
     * (Parent#privateM and Child#privateM are unrelated even with
     * the same name) and static methods hide rather than override.
     * Without this gate the repair pass would link Parent#privateM
     * to Child#privateM and a trace_callers on one would surface
     * callers of the other — exactly the regression the
     * {@code privateMethodsAreNotCrossClassHierarchy} test
     * guards against.
     */
    private static void repairMethodHierarchyViaTypeHierarchy(ProjectIndex index) {
        java.util.List<MethodKey> declared = new java.util.ArrayList<>(index.knownMethods());
        for (MethodKey m : declared) {
            int mMods = index.modifiersOf(m);
            // Skip leaf / non-virtual-dispatch methods: no hierarchy
            // edge to add, and including them would either be a
            // no-op (forward pass already skipped them) or a
            // regression (forward pass correctly skipped them for
            // private/static, we'd wrongly add them back).
            if (mMods == 0) continue;
            if (org.eclipse.jdt.core.dom.Modifier.isPrivate(mMods)) continue;
            if (org.eclipse.jdt.core.dom.Modifier.isStatic(mMods)) continue;
            java.util.Set<String> visited = new java.util.HashSet<>();
            java.util.Deque<String> queue = new java.util.ArrayDeque<>();
            queue.addLast(m.declaringClass);
            visited.add(m.declaringClass);
            while (!queue.isEmpty()) {
                String cls = queue.removeFirst();
                java.util.Set<String> subs = index.subtypesOf(cls);
                if (subs == null) continue;
                for (String sub : subs) {
                    if (!visited.add(sub)) continue;
                    queue.addLast(sub);
                    java.util.Set<MethodKey> candidates = index.methodsInClassWithSignature(sub, m.methodName, m.arity);
                    if (candidates.isEmpty()) continue;
                    for (MethodKey candidate : candidates) {
                        int cMods = index.modifiersOf(candidate);
                        if (cMods == 0) continue;
                        if (org.eclipse.jdt.core.dom.Modifier.isPrivate(cMods)) continue;
                        if (org.eclipse.jdt.core.dom.Modifier.isStatic(cMods)) continue;
                        // Same (name, arity) in a class can match an
                        // overload with different parameter types, not
                        // an override. parameterTypes equality gates the
                        // link so a Parent.m(String) doesn't get a
                        // phantom Child.m(int) sibling.
                        if (!candidate.parameterTypes.equals(m.parameterTypes)) continue;
                        index.recordHierarchy(m, candidate);
                    }
                }
            }
        }
    }

    private void parseFile(Path src, String[] cp, String[] sp, String[] encodingNames,
                           Path projectRoot, ProjectIndex index) {
        char[] content;
        try {
            content = Files.readString(src, StandardCharsets.UTF_8).toCharArray();
        } catch (IOException e) {
            index.recordSkippedFile(src.toString(), "read error: " + e.getMessage());
            return;
        }

        ASTParser parser = ASTParser.newParser(AST.JLS_Latest);
        parser.setSource(content);
        parser.setUnitName(src.toString());
        parser.setEnvironment(cp, sp, encodingNames, true);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);

        CompilationUnit cu;
        try {
            cu = (CompilationUnit) parser.createAST(null);
        } catch (RuntimeException e) {
            index.recordSkippedFile(src.toString(), "parse error: " + e.getMessage());
            return;
        }
        if (cu == null) {
            index.recordSkippedFile(src.toString(), "AST was null");
            return;
        }

        String relPath;
        try {
            relPath = relativize(src, projectRoot);
        } catch (IllegalArgumentException e) {
            index.recordSkippedFile(src.toString(), "not under project root: " + e.getMessage());
            return;
        }
        try {
            cu.accept(new CallSiteVisitor(index, relPath));
        } catch (RuntimeException e) {
            // The visitor itself doesn't throw, but JDT's binding recovery
            // can throw a RuntimeException deep in a BindingResolver
            // callback when a project's source path is misconfigured.
            // The parse phase already returned a (possibly partial) AST;
            // record the failure and let the index carry on without this
            // file's contributions.
            index.recordSkippedFile(src.toString(), "visit error: " + e.getMessage());
        }
    }

    private static String relativize(Path file, Path root) {
        if (root == null) return file.toString();
        // Path#relativize throws IllegalArgumentException if `file` is not
        // under `root`. ProjectLoader guarantees every source is under the
        // project root, so the throw is a real misconfiguration — let it
        // surface to the skipped-files record rather than silently masking
        // it with an absolute path.
        return root.relativize(file).toString();
    }
}
