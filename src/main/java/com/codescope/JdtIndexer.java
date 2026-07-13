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
                    for (MethodKey candidate : index.knownMethods()) {
                        if (!candidate.declaringClass.equals(sub)) continue;
                        if (!candidate.methodName.equals(m.methodName)) continue;
                        if (candidate.arity != m.arity) continue;
                        int cMods = index.modifiersOf(candidate);
                        if (cMods == 0) continue;
                        if (org.eclipse.jdt.core.dom.Modifier.isPrivate(cMods)) continue;
                        if (org.eclipse.jdt.core.dom.Modifier.isStatic(cMods)) continue;
                        index.recordHierarchy(m, candidate);
                    }
                    queue.addLast(sub);
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
            // Record type-hierarchy edges (child FQN → each direct
            // supertype). Used by ProjectIndex.resolveTarget /
            // findInvokedKeys to fall back to inherited methods when
            // the user queries a class or interface that does not
            // declare the target method itself (issue #3:
            // sub-interface inheritance). binding.isFromSource()
            // gates library types — those have no FQN that matches
            // project declarations and would just bloat the index
            // with no payoff (resolveTarget only consults
            // declarations and calls, both project-only).
            //
            // Two sources for the supertype FQNs, in priority order:
            //
            // 1. AST node: `TypeDeclaration.getSuperclassType()` for
            //    classes, `TypeDeclaration.getSuperInterfaceTypes()`
            //    for interfaces. These work even when bindings are
            //    missing or incomplete (e.g. a JAR referenced by the
            //    project but not on the build classpath).
            // 2. `binding.getSuperclass()` / `binding.getInterfaces()`.
            //    For interfaces that `extends` a class
            //    (JDT allows abstract-class supertypes for
            //    interfaces — i.e. `interface IFoo extends AbsBase`)
            //    JDT's ITypeBinding does NOT expose the abstract
            //    superclass: `getSuperclass()` returns null and
            //    `getInterfaces()` is empty. This is a known JDT
            //    limitation that would silently drop the hierarchy
            //    edge if we relied solely on the binding. The AST
            //    node always exposes the textual supertype, so we
            //    use it as the authoritative source.
            recordSupertypesFromAst(node, fqn);
            recordTypeHierarchyFromBinding(fqn, node.resolveBinding());
            return true;
        }

        /**
         * Records one type-hierarchy edge per supertype declared on
         * the AST node. Walks the node's own superclassType (for a
         * class) and superInterfaceTypes (for an interface), reading
         * each supertype's binding — but only stores it when the
         * binding resolves AND is from project source. Library
         * supertypes are intentionally skipped (see
         * {@link #recordTypeHierarchyFromBinding} for the same gate).
         */
        private void recordSupertypesFromAst(TypeDeclaration node, String childFqn) {
            if (childFqn == null || childFqn.isEmpty()) return;
            // Classes extend a single superclass; interfaces extend
            // a list of super-interfaces. JDT models the
            // class-extends-class and interface-extends-interface
            // cases uniformly via getSuperclassType() /
            // getSuperInterfaceTypes(). The interface-extends-class
            // edge (which is what fails on the binding side — see
            // the TypeDeclaration.visit javadoc above) is exposed
            // by getSuperclassType() even on an interface node,
            // because the AST is text-faithful.
            org.eclipse.jdt.core.dom.Type sup = node.getSuperclassType();
            if (sup != null) {
                ITypeBinding sb = sup.resolveBinding();
                if (sb != null && sb.isFromSource()) {
                    String p = eraseTypeArgs(fqnFromBinding(sb));
                    if (p != null && !p.isEmpty()) index.recordTypeHierarchy(childFqn, p);
                }
            }
            for (Object ifaceNode : node.superInterfaceTypes()) {
                org.eclipse.jdt.core.dom.Type iface = (org.eclipse.jdt.core.dom.Type) ifaceNode;
                if (iface == null) continue;
                ITypeBinding sb = iface.resolveBinding();
                if (sb == null || !sb.isFromSource()) continue;
                String p = eraseTypeArgs(fqnFromBinding(sb));
                if (p != null && !p.isEmpty()) index.recordTypeHierarchy(childFqn, p);
            }
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
            recordTypeHierarchyFromBinding(fqn, node.resolveBinding());
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
        public boolean visit(EnumConstantDeclaration node) {
            // `enum E { A(foo()) }` — the args (foo()) are evaluated during
            // the enum class's static init, conceptually equivalent to
            // `public static final E A = new E(foo());`. Attribute calls in
            // the args to <clinit>/0. Pre-Task-4, JDT visited the args inside
            // EnumConstantDeclaration but methodStack was empty (we're inside
            // visit(EnumDeclaration), not inside a MethodDeclaration), so
            // recordCall dropped the edge. Push a synthetic <clinit>/0
            // MethodContext keyed to the enclosing enum so the calls attribute.
            // The synthetic fqn carries the `/0` arity suffix to match the
            // spec's attribution table (docs/superpowers/specs/2026-07-04-
            // coverage-gaps-design.md:44) and the shape used by
            // visit(Initializer) and visit(FieldDeclaration) above.
            // First-wins: if a class has multiple init sites, the declaration
            // points at the first one encountered.
            String cls = currentClass();
            MethodKey key = new MethodKey(cls, "<clinit>", 0, List.of());
            int line = cuLine(node);
            if (index.declarationOf(key) == null) {
                index.putDeclaration(key, new ProjectIndex.SourceLoc(file, line));
                index.recordSymbol(new ProjectIndex.Symbol(
                        "<clinit>", "synthetic",
                        cls + ".<clinit>" + "/0", cls, file, line, null));
            }
            methodStack.push(new MethodContext(key));
            return true;  // descend so arg expressions get visited
        }

        @Override
        public void endVisit(EnumConstantDeclaration node) {
            methodStack.pop();
        }

        @Override
        public boolean visit(RecordDeclaration node) {
            String fqn = fqnOfType(nameOf(node));
            typeStack.push(fqn);
            recordTypeSymbol(node, fqn, "record");
            recordTypeHierarchyFromBinding(fqn, node.resolveBinding());
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
            recordTypeHierarchyFromBinding(fqn, node.resolveBinding());
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
                // For type-variable parameters (`<T> void m(T x)`), use
                // the upper-bound's erasure so the key matches call-site
                // bindings (which have the substituted type, e.g. `String`).
                String name;
                if (tb == null) {
                    name = svd.getType().toString();
                } else {
                    name = tb.getErasure().getQualifiedName();
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
            int line = cuLine(node.getName());
            index.putDeclaration(callerKey, new ProjectIndex.SourceLoc(file, line));
            // Record the JDT modifier bitmask so the post-build
            // reverse-hierarchy repair pass can skip private/static
            // methods (which are not virtual dispatch and must not
            // be linked across the hierarchy).
            index.recordMethodDeclarationModifiers(callerKey, node.getModifiers());
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
        public boolean visit(Initializer node) {
            // Static {} and instance {} blocks: JDT visits the calls inside
            // but methodStack is empty here (we're not inside a
            // MethodDeclaration), so recordCall would silently drop them
            // (recordCall's `methodStack.isEmpty()` early-return).
            // Push a synthetic MethodContext keyed by <clinit> (static) or
            // <class-init> (instance) so calls inside the block attribute
            // to that key instead.
            //
            // <clinit> mirrors the JVM-level identifier for static
            // initializers (JLS §2.9). <class-init> is intentionally NOT
            // <init>: <init> is the JVM identifier for constructors, but
            // JDT uses the class simple name for MethodDeclaration
            // constructors (see comment at visit(MethodDeclaration) above),
            // so a synthetic key named <init> would not collide — however
            // field initializers are inlined into EVERY constructor at
            // runtime (no single static caller), so the design (F6 spec)
            // uses <class-init> to keep instance-block attribution
            // distinct from any explicit constructor attribution. Both
            // names start with `<`, which no source method name can
            // start with (Java identifiers may not contain `<`), so they
            // cannot collide with user-facing methods.
            String cls = currentClass();
            boolean isStatic = Modifier.isStatic(node.getModifiers());
            String name = isStatic ? "<clinit>" : "<class-init>";
            MethodKey key = new MethodKey(cls, name, 0, List.of());
            int line = cuLine(node);
            // First-wins: if a class has multiple init blocks, the
            // declaration points at the first one encountered. Subsequent
            // pushes don't overwrite the recorded declaration — the index
            // uses putDeclaration's "first writer wins" semantics so later
            // blocks inherit the first block's source location. The
            // symbol entry follows the same rule via recordSymbol's dedup
            // by FQN (Container#name/arity).
            if (index.declarationOf(key) == null) {
                index.putDeclaration(key, new ProjectIndex.SourceLoc(file, line));
                index.recordSymbol(new ProjectIndex.Symbol(
                        name, "synthetic",
                        cls + "." + name + "/0", cls, file, line, null));
            }
            methodStack.push(new MethodContext(key));
            return true;
        }

        @Override
        public void endVisit(Initializer node) {
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
            // Push a synthetic MethodContext so calls inside initializer
            // expressions (e.g. `private Logger log = LoggerFactory.get();`)
            // are attributed instead of dropped by recordCall's
            // methodStack.isEmpty() early-return. Static fields → <clinit>,
            // instance fields → <class-init>. The synthetic fqn carries the
            // `/0` arity suffix to match the spec's attribution table
            // (docs/superpowers/specs/2026-07-04-coverage-gaps-design.md:44)
            // and the shape used by visit(Initializer) above. First-wins:
            // if a class has multiple field initializers, the declaration
            // points at the first one encountered (same rule as init blocks).
            boolean isStatic = Modifier.isStatic(node.getModifiers());
            String synthName = isStatic ? "<clinit>" : "<class-init>";
            MethodKey synthKey = new MethodKey(callerClass, synthName, 0, List.of());
            if (index.declarationOf(synthKey) == null) {
                index.putDeclaration(synthKey, new ProjectIndex.SourceLoc(file, line));
                index.recordSymbol(new ProjectIndex.Symbol(
                        synthName, "synthetic",
                        callerClass + "." + synthName + "/0", callerClass, file, line, null));
            }
            methodStack.push(new MethodContext(synthKey));
            return true;  // descend into fragments so initializer calls get visited
        }

        @Override
        public void endVisit(FieldDeclaration node) {
            methodStack.pop();
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
            // For a generic method, the binding returned at a call site
            // has substituted parameter types (e.g. process(String) for
            // a call site to process("hi")), while the declaration side
            // stored the formal types (process(T)). These keys never
            // match, so a BFS for the declaration misses every call site.
            //
            // getMethodDeclaration() returns the original (unsubstituted)
            // method binding — use it to recover the formal parameter
            // types. For non-generic methods it's a no-op.
            IMethodBinding formal = b.getMethodDeclaration();
            IMethodBinding src = formal != null ? formal : b;
            // Use the formal (declaration) declaring class so the key
            // matches the declaration's record. For non-generic methods
            // this is the same class; for generic methods it strips the
            // type arguments (GenericHost<String> → GenericHost).
            ITypeBinding dcFormal = src.getDeclaringClass();
            String dcKey = fqnFromBinding(dcFormal);
            if (dcKey == null) dcKey = dcFqn;
            ITypeBinding[] pts = src.getParameterTypes();
            List<String> paramTypes = new ArrayList<>(pts.length);
            for (ITypeBinding pt : pts) paramTypes.add(erasedTypeNameOf(pt));
            return new MethodKey(dcKey, b.getName(), pts.length, paramTypes);
        }

        /**
         * Canonical name for a parameter type that erases type variables
         * to their bound's erasure (or {@code java.lang.Object} if
         * unbounded). JDT's {@code IMethodBinding.getParameterTypes()}
         * gives different shapes on the two sides of a call for a
         * generic method:
         *
         * <ul>
         *   <li>Declaration side: the parameter type is the type variable
         *   (e.g. {@code T}), and the declaring class is the raw class
         *   (e.g. {@code GenericHost}).</li>
         *   <li>Call site: the parameter type is the substituted type
         *   (e.g. {@code java.lang.String}), and the declaring class is
         *   the parameterized class (e.g. {@code GenericHost<String>}).</li>
         * </ul>
         *
         * <p>Without normalization, a BFS for {@code process/1(T)} (the
         * declaration key) can't find call sites that bind to
         * {@code process/1(String)} — the chain stops at depth 0 even
         * though the call is right there in the source.
         *
         * <p>Calling {@code getErasure()} on a type binding returns the
         * raw type for parameterized types ({@code List<String>} →
         * {@code java.util.List}) and replaces type variables with their
         * upper bound's erasure (or {@code Object} if unbounded). That's
         * exactly what we need for both sides to agree.
         */
        private String erasedTypeNameOf(ITypeBinding tb) {
            ITypeBinding erased = tb.getErasure();
            return erased.getQualifiedName();
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
        private void recordTypeHierarchyFromBinding(String childFqn, ITypeBinding binding) {
            if (binding == null || childFqn == null || childFqn.isEmpty()) return;
            // Only project types contribute to the hierarchy — library
            // types' supertypes are never project declarations, so
            // storing them would just create dead edges. Same gate
            // recordMethodHierarchy uses transitively.
            if (!binding.isFromSource()) return;
            // Strip type arguments from both the child FQN and the
            // supertype FQNs so they match the non-generic FQNs used
            // by declarations (which are always type-erased — see
            // methodKeyOf's use of getMethodDeclaration()).
            // Otherwise a generic supertype like IBase<TicketDTO>
            // would be written under a different key from the IBase
            // declaration, breaking resolveTargetViaAncestors'
            // upward walk.
            String childKey = eraseTypeArgs(fqnFromBinding(binding));
            if (childKey == null || childKey.isEmpty()) return;
            ITypeBinding superclass = binding.getSuperclass();
            if (superclass != null && superclass.isFromSource()) {
                String p = eraseTypeArgs(fqnFromBinding(superclass));
                if (p != null && !p.isEmpty()) index.recordTypeHierarchy(childKey, p);
            }
            for (ITypeBinding iface : binding.getInterfaces()) {
                if (iface == null || !iface.isFromSource()) continue;
                String p = eraseTypeArgs(fqnFromBinding(iface));
                if (p != null && !p.isEmpty()) index.recordTypeHierarchy(childKey, p);
            }
        }

        /**
         * Strips any type-argument suffix from a type FQN
         * ({@code com.example.IBase<com.example.TicketDTO>} →
         * {@code com.example.IBase}). Declarations are indexed under
         * the erased form, so the type hierarchy must use the same
         * form for the upward walk in
         * {@link ProjectIndex#resolveTargetViaAncestors} to land on
         * a real declaration entry.
         */
        private static String eraseTypeArgs(String fqn) {
            if (fqn == null) return null;
            int lt = fqn.indexOf('<');
            return lt < 0 ? fqn : fqn.substring(0, lt);
        }

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

            // Two parallel supertype walks: one driven by JDT
            // bindings (fast, but misses the "interface extends
            // abstract class" edge — see comments below), one driven
            // by AST node text (slow, but always text-faithful). We
            // union the matches.
            //
            // JDT 3.45 binding walk:
            //   - class → seed superclass + interfaces
            //   - interface → seed interfaces only (getSuperclass is
            //     null). When an interface extends an abstract class
            //     (legal Java: `interface IFoo extends AbsBase`),
            //     JDT models the abstract supertype as a SUPER
            //     INTERFACE (per the dump_iface2 probe), but only on
            //     the AST node — ITypeBinding.getInterfaces() returns
            //     an empty array for the same interface. The binding
            //     walk therefore misses the abstract superclass and
            //     would leave the method-hierarchy edge dangling.
            //
            // AST walk: TypeDeclaration.getSuperclassType() returns
            // null for an interface regardless of what `extends`
            // names (the AST is text-faithful but the slot is for
            // "the extends clause when it's a class" — JDT routes
            // extends-class-as-superinterface through
            // superInterfaceTypes()). So we look at BOTH slots and
            // let the visitor / findBinding bridge them.
            java.util.Set<org.eclipse.jdt.core.dom.ITypeBinding> visited =
                    new java.util.HashSet<>();
            java.util.Deque<org.eclipse.jdt.core.dom.ITypeBinding> queue =
                    new java.util.ArrayDeque<>();
            // Seed from bindings (cheap).
            if (dc.getSuperclass() != null) queue.add(dc.getSuperclass());
            for (org.eclipse.jdt.core.dom.ITypeBinding iface : dc.getInterfaces()) {
                queue.add(iface);
            }
            // Seed from the AST node — only valid if we can find the
            // AST node behind `dc`. We don't have a direct path here
            // (recordMethodHierarchy is called from
            // visit(MethodDeclaration), but binding may have resolved
            // to a synthetic / outer scope node). We try to recover
            // the AST node by walking the type binding; the safest
            // fallback is to consult the indexer's
            // recordSupertypesFromAst results, but those are visited
            // at a different time. For the binding-only walk below
            // we rely on JDT, and accept that some deep hierarchies
            // (interface-extends-class with bridge methods) may need
            // a separate repair pass. The Pass-3 ancestor walk in
            // ProjectIndex.resolveTarget catches that fallback at
            // query time via typeHierarchy, which IS recorded from
            // AST. The remaining gap is method-hierarchy edges that
            // recordMethodHierarchy alone would have added but for
            // the binding walk missing the super-interface edge.
            //
            // To close that gap, we re-seed the queue from the AST
            // node representing `dc` if the indexer's
            // recordSupertypesFromBinding saw the binding-side
            // missing edge. The simplest correct path: at every
            // queue pop, after processing st.getDeclaredMethods(),
            // also enqueue st's AST superclassType / superInterfaceTypes
            // bindings. JDT bridges these to ITypeBinding via
            // Type.resolveBinding(). This catches the
            // interface-extends-class case because the AST node
            // exposes AbstractService as a superInterfaceType on the
            // ITicketPredealDomainService TypeDeclaration node.
            // method-hierarchy repair via reverse typeHierarchy walk happens in
            // JdtIndexer.repairMethodHierarchyViaTypeHierarchy once
            // all sources have been visited and typeHierarchy is
            // fully populated. This forward binding walk still adds
            // the common-case edges (class extends class, interface
            // extends interface) so callers do not need to wait for
            // the post-pass.
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
