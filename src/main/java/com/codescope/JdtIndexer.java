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
        return index;
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

    /**
     * AST visitor that records method declarations and call edges. Pushes the
     * enclosing type onto a stack on every type-declaration entry so that
     * {@code visit(MethodDeclaration)} can attribute each method to its
     * declaring FQN.
     *
     * <p>Covers {@code class}/{@code interface} (TypeDeclaration),
     * {@code enum}, {@code record}, and {@code @interface} declarations.
     * In JDT 3.45 with bindings enabled, top-level records may be wrapped in
     * an {@link ImplicitTypeDeclaration}; we enter/exit that too for forward
     * compatibility, but its body only carries the canonical constructor
     * (a known JDT 3.45 limitation — see {@code Point} test fixture).
     */
    private static final class CallSiteVisitor extends ASTVisitor {
        private final ProjectIndex index;
        private final String file;
        private final Deque<MethodContext> methodStack = new ArrayDeque<>();
        private final Deque<String> typeStack = new ArrayDeque<>();
        private String packageName = "";

        CallSiteVisitor(ProjectIndex index, String file) {
            this.index = index;
            this.file = file;
        }

        @Override
        public boolean visit(PackageDeclaration node) {
            packageName = node.getName().getFullyQualifiedName();
            return true;
        }

        @Override
        public boolean visit(TypeDeclaration node) {
            String fqn = fqnOfType(nameOf(node));
            typeStack.push(fqn);
            recordTypeSymbol(node, fqn, node.isInterface() ? "interface" : "class");
            return true;
        }

        @Override
        public void endVisit(TypeDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(EnumDeclaration node) {
            String fqn = fqnOfType(nameOf(node));
            typeStack.push(fqn);
            recordTypeSymbol(node, fqn, "enum");
            // Enum constants are NOT FieldDeclaration nodes in JDT — they
            // have their own EnumConstantDeclaration. Record each as a
            // "field" so find_symbols can find them by name.
            String callerClass = fqn;
            int typeLine = cuLine(node);
            for (Object c : node.enumConstants()) {
                EnumConstantDeclaration ec = (EnumConstantDeclaration) c;
                String constName = ec.getName().getIdentifier();
                index.recordSymbol(new ProjectIndex.Symbol(
                        constName, "field",
                        callerClass + "." + constName,
                        callerClass, file, typeLine, null));
            }
            return true;
        }

        @Override
        public void endVisit(EnumDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(RecordDeclaration node) {
            String fqn = fqnOfType(nameOf(node));
            typeStack.push(fqn);
            recordTypeSymbol(node, fqn, "record");
            // Record components (e.g. `int x, int y` in `record Point(int x, int y)`)
            // are NOT FieldDeclaration nodes — JDT models them as the
            // canonical constructor's parameters. Record each as a "field"
            // so find_symbols can find them by name (and to expose the
            // record's data shape, which is its main purpose).
            String callerClass = fqn;
            int typeLine = cuLine(node);
            for (Object rc : node.recordComponents()) {
                org.eclipse.jdt.core.dom.SingleVariableDeclaration svd =
                        (org.eclipse.jdt.core.dom.SingleVariableDeclaration) rc;
                String compName = svd.getName().getIdentifier();
                index.recordSymbol(new ProjectIndex.Symbol(
                        compName, "field",
                        callerClass + "." + compName,
                        callerClass, file, typeLine, null));
            }
            return true;
        }

        @Override
        public void endVisit(RecordDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(AnnotationTypeDeclaration node) {
            String fqn = fqnOfType(nameOf(node));
            typeStack.push(fqn);
            recordTypeSymbol(node, fqn, "annotation");
            return true;
        }

        @Override
        public void endVisit(AnnotationTypeDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(ImplicitTypeDeclaration node) {
            // JDT 3.45 wraps top-level records here when bindings are enabled.
            // The name is empty; we still enter so nested visits are scoped.
            // No symbol recorded: the wrapped record is visited separately
            // via visit(RecordDeclaration) and recorded there.
            typeStack.push(fqnOfType(nameOf(node)));
            return true;
        }

        @Override
        public boolean visit(AnonymousClassDeclaration node) {
            // `new Foo() { void bar() {} }` introduces a synthesized type.
            // ITypeBinding.getQualifiedName() returns "" for anonymous
            // classes; pushing that produced FQNs like "x.Outer." (trailing
            // dot, empty segment) for the *declaration* side, while the
            // call-site path uses getDeclaringClass().getQualifiedName()
            // which is also "" — different shapes, so the call edge
            // silently split off from the declaration and traceCallers
            // would miss every caller of an anon-class method.
            // getBinaryName() returns "x.Outer$1" reliably on both sides;
            // convert '$' to '.' for visual consistency with how we report
            // regular nested classes.
            ITypeBinding b = node.resolveBinding();
            String fqn = fqnFromBinding(b);
            if (fqn == null) {
                // Fallback: synthesize a stable-ish name from the enclosing
                // type and the anon's source position. Worse than the
                // binding-derived name (no cross-file deduplication) but at
                // least well-formed.
                String enclosing = typeStack.isEmpty() ? packageName : typeStack.peek();
                fqn = (enclosing.isEmpty() ? "" : enclosing + ".") + "<anon@" + node.getStartPosition() + ">";
            }
            typeStack.push(fqn);
            // Anonymous classes are not recorded as symbols — their FQN is
            // a synthesized identity (e.g. "x.Outer$1") that doesn't match
            // any user-facing concept. Members declared inside them are
            // still attributed to the synthesized FQN for call-edge
            // correctness, so trace_callers works for them.
            return true;
        }

        @Override
        public void endVisit(AnonymousClassDeclaration node) {
            typeStack.pop();
        }

        @Override
        public void endVisit(ImplicitTypeDeclaration node) {
            typeStack.pop();
        }

        private static String nameOf(AbstractTypeDeclaration node) {
            return node.getName() == null ? "" : node.getName().getFullyQualifiedName();
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            String callerClass = currentClass();
            List<String> paramTypes = new ArrayList<>();
            for (Object p : node.parameters()) {
                org.eclipse.jdt.core.dom.SingleVariableDeclaration svd =
                        (org.eclipse.jdt.core.dom.SingleVariableDeclaration) p;
                org.eclipse.jdt.core.dom.ITypeBinding tb = svd.getType().resolveBinding();
                // svd.getType() for a varargs parameter returns the element
                // type (not the array), so the binding's getQualifiedName()
                // is just the element name. We need to add `[]` for the
                // varargs marker and for any extra dimensions declared
                // after the parameter name (`String args[]` style).
                String name;
                if (tb == null) {
                    name = svd.getType().toString();
                } else {
                    name = tb.getQualifiedName();
                    if (svd.isVarargs()) name = name + "[]";
                    if (svd.getExtraDimensions() > 0) {
                        for (int i = 0; i < svd.getExtraDimensions(); i++) name = name + "[]";
                    }
                }
                paramTypes.add(name);
            }
            String methodName = node.getName().getIdentifier();
            MethodKey callerKey = new MethodKey(
                    callerClass, methodName,
                    paramTypes.size(), paramTypes);
            int line = cuLine(node);
            index.putDeclaration(callerKey, new ProjectIndex.SourceLoc(file, line));
            // Walk the declaring type's supertypes to find methods with
            // the same name+arity — those are M's hierarchy siblings
            // (overridden in superclass, or declared in a super-interface
            // that M implements). Used by CallChainAnalyzer to cross
            // interface boundaries. See ProjectIndex.recordHierarchy.
            IMethodBinding declBinding = node.resolveBinding();
            if (declBinding != null) {
                recordMethodHierarchy(declBinding);
            }
            methodStack.push(new MethodContext(callerKey));
            // Also record a method/constructor symbol for find_symbols.
            // JDT's MethodDeclaration covers both regular methods and
            // constructors; isConstructor() distinguishes them. The
            // constructor's name in JDT is the class simple name, not
            // "<init>" (that's the JVM-level identifier) — using the JDT
            // form is more user-friendly for search.
            recordMethodSymbol(callerClass, methodName, paramTypes, line,
                    node.isConstructor() ? "constructor" : "method");
            return true;
        }

        @Override
        public void endVisit(MethodDeclaration node) {
            methodStack.pop();
        }

        @Override
        public boolean visit(FieldDeclaration node) {
            // A single FieldDeclaration can declare multiple variables
            // (e.g. `int a, b, c;`) — each is a separate VariableDeclarationFragment
            // and we record one symbol per fragment. The line is the
            // declaration's line (the modifiers' line); all fragments on
            // the same line share it, which matches how IDEs show fields.
            String callerClass = currentClass();
            int line = cuLine(node);
            for (Object f : node.fragments()) {
                VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
                String fieldName = frag.getName().getIdentifier();
                index.recordSymbol(new ProjectIndex.Symbol(
                        fieldName,
                        "field",
                        callerClass + "." + fieldName,
                        callerClass,
                        file,
                        line,
                        null));
            }
            return false;  // don't descend into the fragments (no nested methods/classes to find)
        }

        @Override
        public boolean visit(MethodInvocation node) {
            recordCall(node.resolveMethodBinding(), node);
            return true;
        }

        @Override
        public boolean visit(SuperMethodInvocation node) {
            recordCall(node.resolveMethodBinding(), node);
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            recordCall(node.resolveConstructorBinding(), node);
            return true;
        }

        @Override
        public boolean visit(ConstructorInvocation node) {
            recordCall(node.resolveConstructorBinding(), node);
            return true;
        }

        @Override
        public boolean visit(SuperConstructorInvocation node) {
            recordCall(node.resolveConstructorBinding(), node);
            return true;
        }

        @Override
        public boolean visit(ExpressionMethodReference node) {
            // `expr::m` (instance or static method reference) — the
            // resolved binding points at the target method, just like
            // MethodInvocation. recordCall uses the enclosing method
            // as the caller.
            recordCall(node.resolveMethodBinding(), node);
            return true;
        }

        @Override
        public boolean visit(CreationReference node) {
            // `Type::new` — constructor reference. resolveMethodBinding
            // returns the constructor as a method binding.
            recordCall(node.resolveMethodBinding(), node);
            return true;
        }

        @Override
        public boolean visit(SuperMethodReference node) {
            // `super::m` — same shape as SuperMethodInvocation.
            recordCall(node.resolveMethodBinding(), node);
            return true;
        }

        @Override
        public boolean visit(TypeMethodReference node) {
            // `Type<R>::m` — method reference on a parameterized type.
            // resolveMethodBinding returns the target method.
            recordCall(node.resolveMethodBinding(), node);
            return true;
        }

        private void recordCall(IMethodBinding binding, ASTNode node) {
            if (binding == null) return;
            if (methodStack.isEmpty()) return;
            MethodKey target = methodKeyOf(binding);
            if (target == null) return;
            MethodKey caller = methodStack.peek().key;
            // recordInvocation(caller, callee): the enclosing method is the
            // caller, the resolved binding is the callee being invoked.
            index.recordInvocation(caller, target);
            // Also record the source position of the call expression so
            // find_call_sites can answer "on which line is this called?".
            // cuLine(node) maps node.getStartPosition() to a 1-based line
            // via the CompilationUnit line table; same helper used for
            // method declarations in visit(MethodDeclaration).
            index.recordCallSite(caller, target, new ProjectIndex.SourceLoc(file, cuLine(node)));
        }

        private MethodKey methodKeyOf(IMethodBinding b) {
            ITypeBinding dc = b.getDeclaringClass();
            if (dc == null) return null;
            String dcFqn = fqnFromBinding(dc);
            if (dcFqn == null) return null;
            ITypeBinding[] pts = b.getParameterTypes();
            List<String> paramTypes = new ArrayList<>(pts.length);
            for (ITypeBinding pt : pts) paramTypes.add(pt.getQualifiedName());
            return new MethodKey(dcFqn, b.getName(), pts.length, paramTypes);
        }

        /**
         * Walks the declaring type of {@code b} and its supertypes, looking
         * for methods with the same name + arity. Each match is recorded
         * as a hierarchy sibling of {@code b} via
         * {@link ProjectIndex#recordHierarchy}, so a BFS expanding {@code b}
         * can also expand its overrides / implementors.
         *
         * <p>Walks the full supertype chain (superclass + interfaces +
         * super-interfaces) so diamond inheritance is handled: if a class
         * D implements I1 and I2, and both declare {@code m()}, then
         * D#m ends up related to both I1#m and I2#m. The walk is bounded
         * by the size of the type hierarchy (small in practice) and
         * deduplicated by a visited set, so a diamond doesn't re-walk
         * any type.
         *
         * <p>Synthetic methods (e.g. JDT bridge methods for generic
         * erasure) are filtered out by binding.isSynthetic() — they
         * would otherwise pollute the index with non-user-facing
         * entries.
         */
        private void recordMethodHierarchy(IMethodBinding b) {
            if (b == null) return;
            ITypeBinding dc = b.getDeclaringClass();
            if (dc == null) return;
            MethodKey myKey = methodKeyOf(b);
            if (myKey == null) return;
            // Private and static methods don't participate in virtual
            // dispatch — skip the entire supertype walk for them. A
            // private method in Child shadows the superclass's
            // same-named method lexically but is NOT an override; a
            // static method in Child hides the superclass's static
            // but is also NOT an override. Linking either would let a
            // BFS that lands on Child#privateM or Child#staticM pick
            // up callers of the (different) Parent#publicM as phantom
            // callers.
            int myMods = b.getModifiers();
            if (Modifier.isPrivate(myMods) || Modifier.isStatic(myMods)) return;
            String myName = b.getName();
            int myArity = b.getParameterTypes().length;

            Set<org.eclipse.jdt.core.dom.ITypeBinding> visited =
                    new HashSet<>();
            Deque<org.eclipse.jdt.core.dom.ITypeBinding> queue =
                    new ArrayDeque<>();
            // Seed with the direct supertypes of M's declaring class.
            // For a class: superclass + implemented interfaces.
            // For an interface: super-interfaces (getSuperclass() is null).
            if (dc.getSuperclass() != null) queue.add(dc.getSuperclass());
            for (org.eclipse.jdt.core.dom.ITypeBinding iface : dc.getInterfaces()) {
                queue.add(iface);
            }
            while (!queue.isEmpty()) {
                org.eclipse.jdt.core.dom.ITypeBinding st = queue.removeFirst();
                if (st == null || !visited.add(st)) continue;
                for (IMethodBinding m : st.getDeclaredMethods()) {
                    if (m.isSynthetic()) continue;
                    // Symmetric guard: a supertype's private/static
                    // method is not a valid override target for anything
                    // (private methods aren't visible to subclasses;
                    // static methods are hidden, not overridden). This
                    // is the dual of the early-return guard above and
                    // keeps the index clean even if a future refactor
                    // drops the early return.
                    int mMods = m.getModifiers();
                    if (Modifier.isPrivate(mMods) || Modifier.isStatic(mMods)) continue;
                    if (!m.getName().equals(myName)) continue;
                    if (m.getParameterTypes().length != myArity) continue;
                    MethodKey parentKey = methodKeyOf(m);
                    if (parentKey != null) {
                        index.recordHierarchy(myKey, parentKey);
                    }
                }
                // Recurse into this supertype's own supertypes — captures
                // the I2-extends-I1 case where I2 inherits m from I1.
                if (st.getSuperclass() != null) queue.add(st.getSuperclass());
                for (org.eclipse.jdt.core.dom.ITypeBinding iface : st.getInterfaces()) {
                    queue.add(iface);
                }
            }
        }

        /**
         * Records a type symbol for the indexer's symbol table. Skips
         * empty/blank names (which can happen for {@link
         * ImplicitTypeDeclaration} wrappers in JDT 3.45) so they don't
         * pollute search results. Container is the FQN of the
         * immediately enclosing TYPE, or null for top-level types —
         * distinguished from the package prefix by comparing against
         * {@link #packageName}. The line uses the type-name's start
         * position (not the declaration's), so a leading Javadoc
         * doesn't push the line up.
         */
        private void recordTypeSymbol(ASTNode node, String fqn, String kind) {
            if (fqn == null || fqn.isEmpty()) return;
            int dot = fqn.lastIndexOf('.');
            String simpleName = dot < 0 ? fqn : fqn.substring(dot + 1);
            if (simpleName.isEmpty()) return;
            String rawContainer = dot < 0 ? null : fqn.substring(0, dot);
            String container = packageName.equals(rawContainer) ? null : rawContainer;
            int line = node instanceof AbstractTypeDeclaration atd && atd.getName() != null
                    ? cuLine(atd.getName())
                    : cuLine(node);
            index.recordSymbol(new ProjectIndex.Symbol(
                    simpleName, kind, fqn, container, file, line, null));
        }

        /**
         * Records a method/constructor symbol. The fqn uses the
         * {@code Container#name/arity} shape (matching {@link
         * MethodKey#shortSignature}) so a search hit for a method name
         * produces a stable identifier the caller can hand back to
         * {@code trace_callers} or {@code find_call_sites} without
         * re-parsing.
         */
        private void recordMethodSymbol(String declaringClass, String name,
                                        List<String> paramTypes, int line, String kind) {
            String signature = formatSignature(paramTypes);
            String fqn = declaringClass + "#" + name + "/" + paramTypes.size();
            index.recordSymbol(new ProjectIndex.Symbol(
                    name, kind, fqn, declaringClass, file, line, signature));
        }

        private static String formatSignature(List<String> paramTypes) {
            if (paramTypes.isEmpty()) return "";
            return String.join(",", paramTypes);
        }

        /**
         * Resolves an {@link ITypeBinding} to the FQN we use in {@link MethodKey}.
         * Anonymous classes have an empty {@code getQualifiedName()}; we fall
         * back to {@code getBinaryName()} ("x.Outer$1") with {@code $}
         * normalized to {@code .} so the decl side ({@link #fqnOfType}) and
         * the call side ({@link #methodKeyOf}) produce identical strings.
         * Returns {@code null} if the binding has no usable name at all.
         */
        private static String fqnFromBinding(ITypeBinding tb) {
            if (tb == null) return null;
            if (tb.isAnonymous()) {
                String bin = tb.getBinaryName();
                return bin == null ? null : bin.replace('$', '.');
            }
            String q = tb.getQualifiedName();
            return q == null || q.isEmpty() ? null : q;
        }

        /**
         * Computes the FQN for a (non-anonymous) type declaration by joining
         * the enclosing-type FQN (or package) with the type's simple name.
         * The typeStack holds full FQNs, so currentClass is just peek().
         */
        private String fqnOfType(String simpleName) {
            String enclosing = typeStack.isEmpty() ? packageName : typeStack.peek();
            if (enclosing.isEmpty()) return simpleName;
            if (simpleName.isEmpty()) return enclosing;
            return enclosing + "." + simpleName;
        }

        private String currentClass() {
            if (typeStack.isEmpty()) return packageName.isEmpty() ? "<unknown>" : packageName;
            return typeStack.peek();
        }

        private static int cuLine(ASTNode n) {
            CompilationUnit cu = (CompilationUnit) n.getRoot();
            return cu.getLineNumber(n.getStartPosition());
        }
    }

    private record MethodContext(MethodKey key) {}
}
