package com.codescope.index;

import com.codescope.model.MethodKey;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

/**
 * Owns the method-hierarchy-recording logic: the forward binding walk
 * called from {@link CallSiteVisitor} as each method declaration is
 * visited, and the post-pass {@link #repairMethodHierarchy} that
 * closes gaps the forward walk missed (notably
 * {@code interface IFoo extends AbsBase}-style edges).
 *
 * <p>Lifted out of {@link JdtIndexer} so the indexer class stays an
 * orchestrator. Both walks consult {@link ProjectIndex#modifiersOf(int)}
 * so neither private nor static methods participate — virtual dispatch
 * is the model.
 */
public final class MethodHierarchyExtractor {

    private final ProjectIndex index;

    public MethodHierarchyExtractor(ProjectIndex index) {
        this.index = index;
    }

    public MethodKey methodKeyOf(IMethodBinding b) {
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
     * Resolves an {@link ITypeBinding} to the FQN we use in {@link MethodKey}.
     * Anonymous classes have an empty {@code getQualifiedName()}; we fall
     * back to {@code getBinaryName()} ("x.Outer$1") with {@code $}
     * normalized to {@code .} so the decl side and the call side
     * produce identical strings.
     * Returns {@code null} if the binding has no usable name at all.
     */
    public static String fqnFromBinding(ITypeBinding tb) {
        if (tb == null) return null;
        if (tb.isAnonymous() || tb.isLocal()) {
            // Local classes (declared inside a method body) report an empty
            // getQualifiedName() just like anonymous classes; getBinaryName()
            // (e.g. "p.Outer$1Local") is the only reliable identity on both
            // the declaration and call sides.
            String bin = tb.getBinaryName();
            return bin == null ? null : bin.replace('$', '.');
        }
        String q = tb.getQualifiedName();
        return q == null || q.isEmpty() ? null : q;
    }

    /**
     * Forward binding walk: for the given {@code b}, link its
     * MethodKey to every supertype-declared method with the same
     * (name, arity, parameterTypes) that is non-private and non-static.
     * Same body as the previous {@code CallSiteVisitor.recordMethodHierarchy}
     * method body — moved verbatim, with the receiving class changed
     * and the internal helper references rewritten to use this
     * extractor's own helpers.
     */
    public void recordMethodHierarchy(IMethodBinding b) {
        if (b == null) return;
        // Constructors are not virtual dispatch: their name is the class
        // simple name, so a same-named superclass method would otherwise be
        // linked as an override.
        if (b.isConstructor()) return;
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

        // JDT binding supertype walk: seed the queue with the
        // declaring type's superclass and interfaces, then BFS
        // outward, linking every same-named / same-arity /
        // same-parameterTypes method declared on a supertype.
        //
        // Known gap: JDT does not expose an abstract-class supertype
        // of an interface through ITypeBinding — for the legal
        // `interface IFoo extends AbsBase`, getInterfaces() is empty
        // and getSuperclass() is null. The AST node does expose it
        // (as a superInterfaceType), so CallSiteVisitor records the
        // type-hierarchy edge from AST, and the post-build
        // {@link #repairMethodHierarchy} pass rebuilds the
        // method-hierarchy edge by walking subtypes downward. This
        // forward walk therefore covers the common cases (class
        // extends class, interface extends interface) and the repair
        // pass closes the interface-extends-class gap.
        java.util.Set<org.eclipse.jdt.core.dom.ITypeBinding> visited =
                new java.util.HashSet<>();
        java.util.Deque<org.eclipse.jdt.core.dom.ITypeBinding> queue =
                new java.util.ArrayDeque<>();
        // Seed from bindings (cheap).
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
                // Package-private methods are only visible within the same
                // package; linking them across packages would create
                // phantom overrides (javac rejects @Override on such a
                // method, so they are unrelated).
                if (!Modifier.isPublic(mMods) && !Modifier.isProtected(mMods)) {
                    if (!java.util.Objects.equals(st.getPackage().getName(),
                            dc.getPackage().getName())) continue;
                }
                if (!m.getName().equals(myName)) continue;
                if (m.getParameterTypes().length != myArity) continue;
                // Name + arity is necessary but not sufficient for an
                // override — a class that declares both m(String) and
                // m(int) overloads has two unrelated methods, not one
                // override target. JDT's overrides() resolves generic
                // substitution (e.g. Impl.bar(String) overrides I.bar(T)
                // through I<String>), which the exact parameter-type
                // comparison below cannot; keep the exact check as the
                // fallback for cases overrides() doesn't cover.
                MethodKey parentKey = methodKeyOf(m);
                if (parentKey == null) continue;
                if (!b.overrides(m) && !parentKey.parameterTypes.equals(myKey.parameterTypes)) continue;
                index.recordHierarchy(myKey, parentKey);
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
     * One-shot repair pass that adds method-hierarchy edges the
     * forward {@link #recordMethodHierarchy} walk missed. For every
     * declared method M, walks {@code subtypesOf} downward from
     * {@code M.declaringClass} and links M to any same-named,
     * same-arity method declared on a subtype. Catches the
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
    public void repairMethodHierarchy() {
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
            if (index.isConstructor(m)) continue;
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
                        if (index.isConstructor(candidate)) continue;
                        // Package-private supertype methods are only
                        // visible to subtypes in the same package.
                        if (!org.eclipse.jdt.core.dom.Modifier.isPublic(mMods)
                                && !org.eclipse.jdt.core.dom.Modifier.isProtected(mMods)) {
                            if (!java.util.Objects.equals(
                                    index.declaringPackageOf(m),
                                    index.declaringPackageOf(candidate))) continue;
                        }
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
}
