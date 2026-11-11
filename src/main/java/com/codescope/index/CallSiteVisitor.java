package com.codescope.index;

import com.codescope.model.MethodKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImplicitTypeDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
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

/**
 * AST visitor that records method declarations and call edges. Pushes the
 * enclosing type onto a stack on every type-declaration entry so that
 * {@code visit(MethodDeclaration)} can attribute each method to its
 * declaring FQN.
 *
 * <p>Covers {@code class}/{@code interface} (TypeDeclaration),
 * {@code enum}, {@code record}, and {@code @interface} declarations.
 * An {@link ImplicitTypeDeclaration} wrapper is entered/exit'd too as a
 * defensive fallback for AST shapes that wrap a declaration in one.
 */
final class CallSiteVisitor extends ASTVisitor {
    private final ProjectIndex index;
    private final MethodHierarchyExtractor hierarchy;
    private final String file;
    private final Deque<MethodContext> methodStack = new ArrayDeque<>();
    private final Deque<String> typeStack = new ArrayDeque<>();
    private String packageName = "";

    CallSiteVisitor(ProjectIndex index, MethodHierarchyExtractor hierarchy, String file) {
        this.index = index;
        this.hierarchy = hierarchy;
        this.file = file;
    }

    @Override
    public boolean visit(PackageDeclaration node) {
        packageName = node.getName().getFullyQualifiedName();
        return true;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        String fqn = fqnOfTypeDeclaration(node, fqnOfType(nameOf(node)));
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
     * each supertype's binding.
     *
     * <p>Library supertypes are recorded too (issue #9): a project type
     * that extends/implements a library type must still be walkable
     * upward, or an unresolved call on such a receiver (see
     * {@link ProjectIndex#recordUnresolvedCall}) can never be matched.
     * The AST is text-faithful, so this is also the only source for the
     * {@code interface extends SomeClass} edge that JDT's binding model
     * does not expose.
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
            if (sb != null) {
                String p = eraseTypeArgs(MethodHierarchyExtractor.fqnFromBinding(sb));
                if (p != null && !p.isEmpty()) index.recordTypeHierarchy(childFqn, p);
            }
        }
        for (Object ifaceNode : node.superInterfaceTypes()) {
            org.eclipse.jdt.core.dom.Type iface = (org.eclipse.jdt.core.dom.Type) ifaceNode;
            if (iface == null) continue;
            ITypeBinding sb = iface.resolveBinding();
            if (sb == null) continue;
            String p = eraseTypeArgs(MethodHierarchyExtractor.fqnFromBinding(sb));
            if (p != null && !p.isEmpty()) index.recordTypeHierarchy(childFqn, p);
        }
    }

    @Override
    public void endVisit(TypeDeclaration node) {
        typeStack.pop();
    }

    @Override
    public boolean visit(EnumDeclaration node) {
        String fqn = fqnOfTypeDeclaration(node, fqnOfType(nameOf(node)));
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
        String fqn = fqnOfTypeDeclaration(node, fqnOfType(nameOf(node)));
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
        String fqn = fqnOfTypeDeclaration(node, fqnOfType(nameOf(node)));
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
        // Defensive fallback: some AST shapes wrap a declaration in an
        // ImplicitTypeDeclaration. Its name is usually empty, in which
        // case fqnOfType keeps the enclosing type so nested visits stay
        // scoped. No symbol is recorded here — a wrapped record is
        // visited separately via visit(RecordDeclaration).
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
        String fqn = MethodHierarchyExtractor.fqnFromBinding(b);
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
        if (node.isCompactConstructor()) {
            // A record's compact constructor (`public Point { ... }`) has no
            // declared parameters, but its binding carries the record
            // component types. Use those so the declaration key matches the
            // `new Point(1,2)` call-site binding (Point#Point/2), instead of
            // splitting the graph into an arity-0 key with no callers.
            IMethodBinding b = node.resolveBinding();
            if (b != null) {
                for (ITypeBinding pt : b.getParameterTypes()) {
                    paramTypes.add(pt.getErasure().getQualifiedName());
                }
            }
        } else {
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
        index.recordDeclaringPackage(callerKey, packageName);
        if (node.isConstructor()) {
            index.recordConstructor(callerKey);
        }
        // Walk the declaring type's supertypes to find methods with
        // the same name+arity — those are M's hierarchy siblings
        // (overridden in superclass, or declared in a super-interface
        // that M implements). Used by CallChainAnalyzer to cross
        // interface boundaries. See ProjectIndex.recordHierarchy.
        IMethodBinding declBinding = node.resolveBinding();
        if (declBinding != null) {
            hierarchy.recordMethodHierarchy(declBinding);
            // Issue #9: mark framework entry points (Spring handlers,
            // scheduled/event methods, JUnit/TestNG tests, main) so a
            // caller chain ending there is labelled as an entry rather than
            // an unexplored branch.
            String entryKind = EntryPoints.detectKind(declBinding, node.modifiers());
            if (entryKind != null) index.recordEntryPoint(callerKey, entryKind);
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
        recordCall(node.resolveMethodBinding(), node, receiverTypeOf(node.getExpression()));
        return true;
    }

    @Override
    public boolean visit(SuperMethodInvocation node) {
        // `super.m(...)` dispatches on `this`, so the receiver's static
        // type is the enclosing class — NOT the superclass that declares
        // m. Recording the superclass would make every sibling override's
        // super call look like a call on a sibling receiver (issue #7).
        recordCall(node.resolveMethodBinding(), node, currentReceiverClass());
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        // Constructor: not virtual dispatch, receiver type is irrelevant
        // to the hierarchy filter. Pass null (unknown / not applicable).
        recordCall(node.resolveConstructorBinding(), node, null);
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        recordCall(node.resolveConstructorBinding(), node, null);
        return true;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        recordCall(node.resolveConstructorBinding(), node, null);
        return true;
    }

    @Override
    public boolean visit(ExpressionMethodReference node) {
        // `expr::m` (instance or static method reference) — the
        // resolved binding points at the target method, just like
        // MethodInvocation. recordCall uses the enclosing method
        // as the caller.
        recordCall(node.resolveMethodBinding(), node, receiverTypeOf(node.getExpression()));
        return true;
    }

    @Override
    public boolean visit(CreationReference node) {
        // `Type::new` — constructor reference. resolveMethodBinding
        // returns the constructor as a method binding. Not virtual
        // dispatch, so the receiver type is not used by the filter.
        recordCall(node.resolveMethodBinding(), node, null);
        return true;
    }

    @Override
    public boolean visit(SuperMethodReference node) {
        // `super::m` — same shape as SuperMethodInvocation: dispatches on
        // `this`, so the receiver's static type is the enclosing class.
        recordCall(node.resolveMethodBinding(), node, currentReceiverClass());
        return true;
    }

    @Override
    public boolean visit(TypeMethodReference node) {
        // `Type<R>::m` — method reference on a parameterized type.
        // resolveMethodBinding returns the target method. The receiver
        // is an instance of the referenced type.
        recordCall(node.resolveMethodBinding(), node, receiverTypeOfType(node.getType()));
        return true;
    }

    /**
     * Resolves the static type of a call's receiver expression to its
     * erased FQN, for {@link ProjectIndex.CallSite#receiverType()}.
     * A null expression means an implicit {@code this} receiver (a bare
     * {@code m()} call), so the enclosing class is the receiver type.
     * Returns null when the type cannot be resolved — the hierarchy
     * filter treats null as "unknown, don't filter".
     */
    private String receiverTypeOf(Expression expr) {
        if (expr == null) return currentReceiverClass();
        ITypeBinding tb = expr.resolveTypeBinding();
        if (tb == null) return null;
        return eraseTypeArgs(MethodHierarchyExtractor.fqnFromBinding(tb.getErasure()));
    }

    /** As {@link #receiverTypeOf(Expression)}, for a {@code Type::m} reference. */
    private String receiverTypeOfType(org.eclipse.jdt.core.dom.Type type) {
        if (type == null) return currentReceiverClass();
        ITypeBinding tb = type.resolveBinding();
        if (tb == null) return null;
        return eraseTypeArgs(MethodHierarchyExtractor.fqnFromBinding(tb.getErasure()));
    }

    /**
     * The class that owns the call currently being visited: the enclosing
     * method's declaring class. Used as the receiver type for implicit
     * {@code this} and {@code super} calls.
     */
    private String currentReceiverClass() {
        return methodStack.isEmpty() ? currentClass() : methodStack.peek().key.declaringClass;
    }

    private void recordCall(IMethodBinding binding, ASTNode node, String receiverType) {
        if (methodStack.isEmpty()) return;
        MethodKey caller = methodStack.peek().key;
        ProjectIndex.SourceLoc loc = new ProjectIndex.SourceLoc(file, cuLine(node));
        if (binding == null) {
            // JDT could not resolve the invocation (issue #9: a project
            // interface inheriting the method from a class supertype). Keep
            // the call as an unresolved tuple instead of dropping it —
            // query time attributes it by receiver type + signature.
            recordUnresolvedCall(node, caller, receiverType, loc);
            return;
        }
        MethodKey target = hierarchy.methodKeyOf(binding);
        if (target == null) return;
        // recordInvocation(caller, callee): the enclosing method is the
        // caller, the resolved binding is the callee being invoked.
        index.recordInvocation(caller, target);
        // Also record the source position of the call expression so
        // find_call_sites can answer "on which line is this called?".
        // cuLine(node) maps node.getStartPosition() to a 1-based line
        // via the CompilationUnit line table; same helper used for
        // method declarations in visit(MethodDeclaration). The receiver's
        // static type rides along for the issue #7 / #8 hierarchy filter.
        index.recordCallSite(caller, target, loc, receiverType);
    }

    /**
     * Records an unresolved invocation as a {@link ProjectIndex.UnresolvedCall}.
     * Only plain and {@code super} method invocations carry a known name +
     * arity; method references (whose arity depends on the functional
     * interface) are skipped.
     */
    private void recordUnresolvedCall(ASTNode node, MethodKey caller,
                                      String receiverType, ProjectIndex.SourceLoc loc) {
        String name;
        int arity;
        if (node instanceof MethodInvocation mi) {
            name = mi.getName().getIdentifier();
            arity = mi.arguments().size();
        } else if (node instanceof SuperMethodInvocation si) {
            name = si.getName().getIdentifier();
            arity = si.arguments().size();
        } else {
            return;
        }
        index.recordUnresolvedCall(caller, receiverType, name, arity, loc);
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
        // Only project types are entered into the hierarchy as children —
        // we visit project type declarations, so the child is always
        // from source; the gate keeps a recovering/unknown binding from
        // polluting the index.
        if (!binding.isFromSource()) return;
        // Strip type arguments from both the child FQN and the
        // supertype FQNs so they match the non-generic FQNs used
        // by declarations (which are always type-erased — see
        // methodKeyOf's use of getMethodDeclaration()).
        // Otherwise a generic supertype like IBase<TicketDTO>
        // would be written under a different key from the IBase
        // declaration, breaking resolveTargetViaAncestors'
        // upward walk.
        String childKey = eraseTypeArgs(MethodHierarchyExtractor.fqnFromBinding(binding));
        if (childKey == null || childKey.isEmpty()) return;
        // Library supertypes are recorded too (issue #9): the upward walk
        // must reach them so unresolved calls on a receiver whose method
        // comes from a library supertype can be matched, and so
        // isSubtypeOf() can relate a project type to the library types it
        // implements.
        ITypeBinding superclass = binding.getSuperclass();
        if (superclass != null) {
            String p = eraseTypeArgs(MethodHierarchyExtractor.fqnFromBinding(superclass));
            if (p != null && !p.isEmpty()) index.recordTypeHierarchy(childKey, p);
        }
        for (ITypeBinding iface : binding.getInterfaces()) {
            if (iface == null) continue;
            String p = eraseTypeArgs(MethodHierarchyExtractor.fqnFromBinding(iface));
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

    /**
     * FQN for a type declaration. Local classes report an empty
     * {@code getQualifiedName()} (JDT marks them {@code isLocal()}), so the
     * AST-derived {@link #fqnOfType} would disagree with the binding-derived
     * FQN used on the call side. Use the binary name for local types so both
     * sides agree (mirrors the anonymous-class handling in
     * {@link MethodHierarchyExtractor#fqnFromBinding}).
     */
    private static String fqnOfTypeDeclaration(AbstractTypeDeclaration node, String astFqn) {
        ITypeBinding tb = node.resolveBinding();
        if (tb != null && tb.isLocal()) {
            String bin = tb.getBinaryName();
            if (bin != null && !bin.isEmpty()) return bin.replace('$', '.');
        }
        return astFqn;
    }

    private String currentClass() {
        if (typeStack.isEmpty()) return packageName.isEmpty() ? "<unknown>" : packageName;
        return typeStack.peek();
    }

    private static int cuLine(ASTNode n) {
        CompilationUnit cu = (CompilationUnit) n.getRoot();
        return cu.getLineNumber(n.getStartPosition());
    }

    private record MethodContext(MethodKey key) {}
}
