package com.codescope.index;

import com.codescope.model.MethodKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reverse call index for a project. Built once by {@link JdtIndexer}, then
 * queried for chains via {@link CallChainAnalyzer}.
 *
 *   calls[target] = set of methods that invoke target (insertion-ordered, deduped)
 *   declarations[method] = source location of the method definition
 *
 * <p>Thread-safe: writes from the indexer's parallel parse pass must be safe
 * to issue concurrently. The outer maps are {@link ConcurrentHashMap}; the
 * per-target caller set is a {@link LinkedHashSet} guarded by synchronizing
 * on the set itself (since {@code LinkedHashSet} is not thread-safe, but is
 * much cheaper than {@code ArrayList.contains} for dedup on hot methods).
 */
public final class ProjectIndex {
    public record SourceLoc(String file, int line) {}

    /**
     * One recorded call-site fact: where the call expression sits
     * ({@code file} + {@code line}) and the static type of the receiver
     * expression ({@code receiverType}, erased FQN).
     *
     * <p>{@code receiverType} exists for issues #7 / #8: a call site may
     * only be counted as a caller of a target method {@code M} when
     * {@code M.declaringClass} is a subtype of the receiver's static
     * type — otherwise the receiver can never dispatch to {@code M}.
     * Without it, expanding from {@code ChildA#save} to its ancestor
     * {@code Parent#save} would also pull in {@code ChildB#save} (a
     * sibling override that merely calls {@code super.save()}), because
     * both are recorded as callers of the same ancestor.
     *
     * <p>{@code receiverType} is null when the receiver type could not be
     * resolved (a binding failure, or a non-virtual call such as a
     * constructor). {@link #isSubtypeOf} treats null as "unknown" and
     * does not filter on it.
     */
    public record CallSite(String file, int line, String receiverType) {}

    /**
     * A method invocation whose callee JDT could not resolve (issue #9
     * "接口多态未展开"). The known trigger: the receiver's static type is a
     * <em>project interface</em> that inherits the method from a
     * <em>class</em> supertype — JDT does not expose an interface's
     * abstract/library class supertype through
     * {@code ITypeBinding.getInterfaces()}, so {@code resolveMethodBinding()}
     * returns null and the call edge never enters the index.
     *
     * <p>Instead of dropping the call, the indexer records it here with the
     * receiver's static type and the invoked name + arity. Query time then
     * attributes it to any target whose {@code (name, arity)} match and
     * whose declaring class is hierarchy-comparable with the receiver type
     * (see {@link #unresolvedCallersOf(MethodKey)}). This is deliberately a
     * fallback for <em>unresolved</em> bindings only — resolved bindings
     * keep their exact edge.
     *
     * @param caller       the enclosing method (the call-site's caller)
     * @param receiverType the receiver expression's static type (erased FQN),
     *                     or null if even that could not be resolved
     * @param methodName   the invoked method's simple name
     * @param arity        the number of arguments at the call site
     * @param file         the source file of the call expression
     * @param line         the 1-based line of the call expression
     */
    public record UnresolvedCall(MethodKey caller, String receiverType,
                                 String methodName, int arity, String file, int line) {}

    /**
     * One MyBatis mapper statement parsed from an XML file (issue #9 item
     * 4). The XML is the <em>implementation</em> of a mapper interface
     * method: {@code namespace} is the mapper interface FQN and {@code id}
     * is the statement id, so {@code (namespace, id)} pairs with
     * {@code MapperInterface#method}. {@code sql} is the statement body
     * (nested dynamic-SQL tags preserved as text, whitespace collapsed).
     *
     * @param namespace mapper interface FQN (the {@code namespace} attribute)
     * @param id        statement {@code id}
     * @param type      element name: {@code select}, {@code insert},
     *                  {@code update}, {@code delete} or {@code sql}
     * @param sql       the statement body
     * @param file      source path, relative to the project root when possible
     * @param line      1-based line of the statement's start tag
     */
    public record MapperStatement(String namespace, String id, String type,
                                  String sql, String file, int line) {}

    /**
     * A single declared symbol — a type (class/interface/enum/record/annotation),
     * a method, a constructor, or a field. Carries enough metadata for
     * {@code find_symbols} to render a useful search hit without re-walking
     * the AST: the simple name (search key), the FQN (stable identifier),
     * the kind (for filtering), and the source location.
     *
     * <p>For top-level/nested types, {@code fqn} is the type FQN
     * (e.g. {@code com.example.UserRepository}) and {@code container} is
     * the enclosing-type FQN for nested types, or null for top-level
     * types. For methods/constructors, {@code fqn} is
     * {@code Container#name/arity} and {@code container} is the declaring
     * class FQN. For fields, {@code fqn} is {@code Container.fieldName}
     * and {@code container} is the declaring class FQN. {@code signature}
     * carries the method/constructor full parameter-type list, or null
     * for types and fields.
     */
    public record Symbol(
            String name,
            String kind,
            String fqn,
            String container,
            String file,
            int line,
            String signature) {}

    private final Map<MethodKey, Set<MethodKey>> calls = new ConcurrentHashMap<>();
    /**
     * Forward view of the same call edges: {@code invokes[caller]} = the
     * methods {@code caller} directly invokes. Maintained write-through by
     * {@link #recordInvocation} so the downward callee tree
     * ({@code trace_callees}, issue #9) does not have to invert the reverse
     * map per query. Same thread-safety pattern as {@link #calls}.
     */
    private final Map<MethodKey, Set<MethodKey>> invokes = new ConcurrentHashMap<>();
    private final Map<MethodKey, SourceLoc> declarations = new ConcurrentHashMap<>();
    // Visibility / dispatchability of each declared method, recorded
    // by JdtIndexer at the same visit time as the declaration.
    // Currently consumed by the post-build reverse method-hierarchy
    // repair pass so the pass can correctly skip private and static
    // methods (private methods are lexically scoped and not virtual
    // dispatch; static methods hide rather than override). Without
    // this gate, the repair pass would link Parent#privateM to
    // Child#privateM as if they were overrides, and a trace_callers
    // on one would surface callers of the other. See
    // JdtIndexer.repairMethodHierarchyViaTypeHierarchy.
    private final Map<MethodKey, Integer> declarationModifiers = new ConcurrentHashMap<>();
    // Framework entry-point kinds (issue #9): method → label such as
    // "spring-mvc", "spring-scheduled", "junit5", "main". Recorded by the
    // indexer from the method's annotations / signature; surfaced on the
    // node so a caller chain that ends at a Spring handler says so instead
    // of looking like an unexplored branch.
    private final Map<MethodKey, String> entryPoints = new ConcurrentHashMap<>();
    // Constructors are not virtual dispatch: they must never participate in
    // the method-hierarchy walk (a constructor's name is the class simple
    // name, so a same-named superclass method would otherwise be linked as
    // an override). Recorded so the repair pass can skip them too.
    private final Set<MethodKey> constructors = ConcurrentHashMap.newKeySet();
    // Declaring package per method, recorded at declaration time. The
    // method-hierarchy repair pass needs it to tell whether a package-
    // private method is visible across packages (it must not be linked as
    // an override then). The FQN alone can't disambiguate the package for
    // nested types (`a.Outer.Inner` vs `a.Outer` as a package name).
    private final Map<MethodKey, String> declaringPackages = new ConcurrentHashMap<>();
    // Method-hierarchy index: for each MethodKey M, the set of other
    // MethodKeys in M's hierarchy group (the methods M overrides in a
    // supertype, plus the methods that implement M in subtypes). Both
    // directions are recorded bidirectionally by recordHierarchy.
    // Populated eagerly by JdtIndexer during visit(MethodDeclaration) —
    // see recordMethodHierarchy there. Used by CallChainAnalyzer to
    // cross interface boundaries during BFS, so a caller that statically
    // references an interface method is reachable when the BFS reaches
    // a concrete implementation (or vice versa).
    private final Map<MethodKey, Set<MethodKey>> hierarchy = new ConcurrentHashMap<>();
    // Type-hierarchy index, stored as two directed maps:
    //   typeParents[child]   = direct supertypes of child
    //   typeChildren[parent] = direct subtypes of parent
    // recordTypeHierarchy writes both directions so supertypesOf /
    // subtypesOf are each a single O(1) lookup. Used by resolveTarget /
    // findInvokedKeys as a fallback when the user queries a class or
    // interface that does not declare the target method itself but
    // inherits it (e.g. `ISub extends IBase` — ISub has no
    // `InvFundTicketPrint` declaration but its parent IBase does, and
    // calls through an ISub-typed field bind to IBase#m in JDT), and by
    // the reverse-hierarchy repair pass to find same-named methods on
    // subtypes. Populated eagerly by JdtIndexer during
    // visit(TypeDeclaration) / visit(EnumDeclaration) /
    // visit(RecordDeclaration) / visit(AnnotationTypeDeclaration) — see
    // recordTypeHierarchy there.
    private final Map<String, Set<String>> typeParents = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> typeChildren = new ConcurrentHashMap<>();
    // Signature index: for each (methodName, arity) pair, the inner
    // map groups Java overloads by their declaring class (a class may
    // declare save(String) and save(int) under the same (name, arity)
    // key, and both must be reachable as override candidates for the
    // post-build reverse-hierarchy repair pass). Used by the repair
    // pass to find same-named, same-arity candidates in O(1) per
    // signature lookup instead of an O(N) full-scan of
    // {@link #knownMethods()} per subtype — that full scan was the
    // build-phase bottleneck for large projects (issue #6). Populated
    // write-through at {@link #putDeclaration}.
    private final Map<NameArity, Map<String, Set<MethodKey>>> bySignature = new ConcurrentHashMap<>();

    // Composite key for {@link #bySignature}. Package-private; not part of the public API.
    record NameArity(String name, int arity) {}

    private final List<String> skippedFiles = Collections.synchronizedList(new ArrayList<>());
    // Parallel to `calls` but per (callee, caller) edge carries a list of
    // call-site positions. Populated eagerly at index time so that
    // find_call_sites can answer "on which line" without re-walking the AST.
    // Outer key: callee MethodKey. Inner key: caller MethodKey. Value:
    // ordered list of CallSite (the call expression's source position plus
    // the receiver's static type, used for hierarchy filtering — see
    // CallSite). Same thread-safety pattern as `calls` (ConcurrentHashMap
    // outer, synchronized inner).
    private final Map<MethodKey, Map<MethodKey, List<CallSite>>> callSites
            = new ConcurrentHashMap<>();
    // Invocations whose callee binding JDT could not resolve (issue #9
    // interface polymorphism). Kept as (caller, receiverType, name, arity)
    // tuples and attributed at query time by
    // {@link #unresolvedCallersOf(MethodKey)}. Usually empty or tiny; a
    // synchronized list because the parallel parse pass appends to it.
    private final List<UnresolvedCall> unresolvedCalls =
            Collections.synchronizedList(new ArrayList<>());
    // MyBatis mapper statements parsed from XML (issue #9 item 4). Keyed by
    // namespace + id for lookup from a mapper interface method.
    private final List<MapperStatement> mapperStatements =
            Collections.synchronizedList(new ArrayList<>());
    // Flat symbol table populated by JdtIndexer. Outer key: lowercase simple
    // name (for case-insensitive lookup); inner: ordered list of matching
    // symbols (a single name can match many types/methods/fields). Each
    // Symbol carries its own FQN + kind, so a search result row is one
    // Symbol object. Same thread-safety pattern as `calls` /
    // `callSites`. We index by lowercase simple name because find_symbols
    // is a substring-on-name search; substring matching still scans the
    // entry set, so the lowercase key only saves us a toLowerCase per
    // comparison.
    private final Map<String, List<Symbol>> symbols = new ConcurrentHashMap<>();

    /** Records a source file that the indexer could not parse, for diagnostic reporting. */
    public void recordSkippedFile(String path, String reason) {
        skippedFiles.add(path + ": " + reason);
    }

    /** Source files the indexer could not parse (e.g. read errors, syntax errors). */
    public List<String> skippedFiles() {
        synchronized (skippedFiles) {
            return List.copyOf(skippedFiles);
        }
    }

    /**
     * Records that {@code caller} invokes {@code callee}, i.e. the
     * call-edge {@code caller -> callee}. Argument order matches the
     * direction of the call (caller first, callee second) so the call
     * site reads the same as the JDT visit: "this method calls that
     * method".
     */
    public void recordInvocation(MethodKey caller, MethodKey callee) {
        // Dedupe: the same (caller, callee) pair can come from a hot method
        // being called from many sites in the same caller body. LinkedHashSet
        // gives O(1) add/contains while preserving insertion order.
        Set<MethodKey> set = calls.computeIfAbsent(callee, k -> new LinkedHashSet<>());
        synchronized (set) {
            set.add(caller);
        }
        // Forward edge, used by the downward callee tree (trace_callees).
        // Maintained here so the reverse and forward views can never drift.
        Set<MethodKey> fwd = invokes.computeIfAbsent(caller, k -> new LinkedHashSet<>());
        synchronized (fwd) {
            fwd.add(callee);
        }
    }

    /**
     * Records that {@code caller} invokes {@code callee} at source location
     * {@code loc}, on a receiver whose static type is {@code receiverType}
     * (erased FQN, or null if unknown / not applicable). Multiple calls to
     * this method for the same (caller, callee) pair append — they are NOT
     * deduplicated, because two call expressions at different lines are
     * distinct facts (a caller can invoke the same callee from many sites,
     * and the user wants all of them).
     *
     * <p>{@code receiverType} is carried per site (not per edge) because the
     * same caller can invoke the same callee on receivers of different
     * static types from different expressions, and issue #7 / #8 filtering
     * is per expression.
     */
    public void recordCallSite(MethodKey caller, MethodKey callee, SourceLoc loc,
                               String receiverType) {
        Map<MethodKey, List<CallSite>> byCaller =
                callSites.computeIfAbsent(callee, k -> new ConcurrentHashMap<>());
        List<CallSite> sites = byCaller.computeIfAbsent(caller, k ->
                Collections.synchronizedList(new ArrayList<>()));
        synchronized (sites) {
            sites.add(new CallSite(loc.file(), loc.line(), receiverType));
        }
    }

    /**
     * Records a method invocation whose callee binding JDT could not
     * resolve (issue #9 interface polymorphism). The call is kept as a
     * {@link UnresolvedCall} and attributed at query time by
     * {@link #unresolvedCallersOf(MethodKey)} rather than dropped.
     */
    public void recordUnresolvedCall(MethodKey caller, String receiverType,
                                     String methodName, int arity, SourceLoc loc) {
        unresolvedCalls.add(new UnresolvedCall(caller, receiverType, methodName, arity,
                loc.file(), loc.line()));
    }

    /**
     * Returns the unresolved calls that could target {@code target}: same
     * {@code (name, arity)} and a receiver type that is
     * <em>hierarchy-comparable</em> with the target's declaring class.
     *
     * <p>Comparability is deliberately symmetric
     * ({@code isSubtypeOf(target, receiver) || isSubtypeOf(receiver, target)}):
     * a call on a receiver of type {@code R} dispatches into {@code R}'s
     * hierarchy, so a target declared on {@code R} (or a subtype of {@code R})
     * is a possible callee, and so is a target declared on a supertype of
     * {@code R} that {@code R} inherits the method from — which is exactly
     * the interface-inherits-from-class case the binding resolver misses.
     * Sibling classes are <em>not</em> comparable, so they still do not
     * cross-attribute (the issue #7 / #8 invariant).
     *
     * <p>Linear over {@link #unresolvedCalls}, which is normally empty or
     * tiny (only bindings JDT could not resolve).
     */
    public List<UnresolvedCall> unresolvedCallersOf(MethodKey target) {
        List<UnresolvedCall> out = new ArrayList<>();
        synchronized (unresolvedCalls) {
            for (UnresolvedCall u : unresolvedCalls) {
                if (!u.methodName().equals(target.methodName)) continue;
                if (u.arity() != target.arity) continue;
                if (u.receiverType() == null) continue;
                if (isSubtypeOf(target.declaringClass, u.receiverType())
                        || isSubtypeOf(u.receiverType(), target.declaringClass)) {
                    out.add(u);
                }
            }
        }
        return out;
    }

    /**
     * Records a MyBatis mapper statement parsed from XML (issue #9 item 4).
     * Duplicate {@code (namespace, id, type, file, line)} entries are
     * coalesced, matching {@link #recordSymbol}'s dedup rule.
     */
    public void recordMapperStatement(MapperStatement statement) {
        if (statement == null || statement.namespace == null || statement.id == null) return;
        synchronized (mapperStatements) {
            for (MapperStatement existing : mapperStatements) {
                if (existing.namespace.equals(statement.namespace)
                        && existing.id.equals(statement.id)
                        && existing.type.equals(statement.type)
                        && existing.line == statement.line
                        && java.util.Objects.equals(existing.file, statement.file)) {
                    return;
                }
            }
            mapperStatements.add(statement);
        }
    }

    /**
     * Returns every mapper statement for the mapper interface method
     * {@code namespace#method}, in file order. Used by
     * {@code trace_callees} to attach the SQL that implements a mapper
     * interface method.
     */
    public List<MapperStatement> mapperStatementsFor(String namespace, String method) {
        if (namespace == null || method == null) return List.of();
        List<MapperStatement> out = new ArrayList<>();
        synchronized (mapperStatements) {
            for (MapperStatement s : mapperStatements) {
                if (namespace.equals(s.namespace) && method.equals(s.id)) out.add(s);
            }
        }
        return out;
    }

    /** All recorded mapper statements (defensive copy). */
    public List<MapperStatement> mapperStatements() {
        synchronized (mapperStatements) {
            return List.copyOf(mapperStatements);
        }
    }

    public void putDeclaration(MethodKey method, SourceLoc loc) {
        declarations.putIfAbsent(method, loc);
        bySignature
                .computeIfAbsent(new NameArity(method.methodName, method.arity),
                        k -> new ConcurrentHashMap<>())
                .computeIfAbsent(method.declaringClass, k -> ConcurrentHashMap.newKeySet())
                .add(method);
    }

    /**
     * Records the JDT modifier bitmask for a declared method. Used by
     * {@link JdtIndexer} at the same visit that calls
     * {@link #putDeclaration} so the reverse-hierarchy repair pass
     * can correctly gate virtual-dispatch methods (skip private and
     * static). Idempotent — a re-record for the same key is a no-op
     * for callers that already saw the first value.
     */
    public void recordMethodDeclarationModifiers(MethodKey method, int modifiers) {
        declarationModifiers.putIfAbsent(method, modifiers);
    }

    /** Records that {@code method} is a constructor (not virtual dispatch). */
    public void recordConstructor(MethodKey method) {
        constructors.add(method);
    }

    /**
     * Records that {@code method} is a framework entry point of the given
     * kind (e.g. {@code "spring-mvc"}, {@code "main"}). Idempotent: the
     * first recorded kind wins.
     */
    public void recordEntryPoint(MethodKey method, String kind) {
        if (kind == null || kind.isEmpty()) return;
        entryPoints.putIfAbsent(method, kind);
    }

    /**
     * The framework entry-point kind for {@code method}, or null if it is
     * an ordinary method. See {@link EntryPoints}.
     */
    public String entryPointKindOf(MethodKey method) {
        return entryPoints.get(method);
    }

    /** True if {@code method} was recorded as a constructor. */
    public boolean isConstructor(MethodKey method) {
        return constructors.contains(method);
    }

    /** Records the package that declares {@code method}. */
    public void recordDeclaringPackage(MethodKey method, String pkg) {
        declaringPackages.putIfAbsent(method, pkg == null ? "" : pkg);
    }

    /** The package that declares {@code method}, or null if not recorded. */
    public String declaringPackageOf(MethodKey method) {
        return declaringPackages.get(method);
    }

    /**
     * Returns the JDT modifier bitmask recorded for {@code method},
     * or 0 if the indexer never set one (e.g. synthetic methods, or
     * a method the caller didn't pass through the recordMethod… path).
     * Callers should mask the returned value with
     * {@link java.lang.reflect.Modifier#isPrivate(int)} etc.
     */
    public int modifiersOf(MethodKey method) {
        Integer m = declarationModifiers.get(method);
        return m == null ? 0 : m;
    }

    /**
     * Records that {@code child} and {@code parent} belong to the same
     * method-hierarchy group — i.e. they have the same name + arity and
     * are linked by an implements / extends relationship through their
     * declaring types. Stored bidirectionally: callers of either can find
     * the other via {@link #relatedMethods}.
     *
     * <p>Self-pairs are silently skipped. Both keys must already be
     * declared in {@link #declarations} (the indexer records hierarchy
     * from {@code visit(MethodDeclaration)}, which also calls
     * {@code putDeclaration} — so this is naturally true for project
     * methods; library methods recorded via {@code findInvokedKeys} never
     * get hierarchy entries because they are not declared in project
     * sources).
     */
    public void recordHierarchy(MethodKey child, MethodKey parent) {
        if (child == null || parent == null || child.equals(parent)) return;
        hierarchy.computeIfAbsent(child, k -> ConcurrentHashMap.newKeySet()).add(parent);
        hierarchy.computeIfAbsent(parent, k -> ConcurrentHashMap.newKeySet()).add(child);
    }

    /**
     * Records that {@code childType} is a direct subtype of
     * {@code parentType} (a class extending another class, a class
     * implementing an interface, or an interface extending another
     * interface). Stored as two directed edges —
     * {@code typeParents[child] += parent} and
     * {@code typeChildren[parent] += child} — so
     * {@link #supertypesOf} and {@link #subtypesOf} each answer in O(1)
     * per lookup, without re-walking declarations and without conflating
     * the two directions.
     *
     * <p>Self-pairs (a type listed as its own supertype) and nulls are
     * silently skipped. Both FQNs must be non-empty — a top-level type
     * with no package gets the empty-string parent from JDT in some
     * edge cases (e.g. unpackaged test sources), and an empty-string
     * edge would just pollute every other top-level type's subtype set.
     */
    public void recordTypeHierarchy(String childType, String parentType) {
        if (childType == null || parentType == null) return;
        if (childType.isEmpty() || parentType.isEmpty()) return;
        if (childType.equals(parentType)) return;
        typeParents.computeIfAbsent(childType, k -> ConcurrentHashMap.newKeySet()).add(parentType);
        typeChildren.computeIfAbsent(parentType, k -> ConcurrentHashMap.newKeySet()).add(childType);
    }

    /**
     * Returns the set of type FQNs that extend or implement
     * {@code parentType} directly. The result includes only types that
     * were recorded by the indexer — if no project source declares a
     * subtype, this returns an empty set (not null).
     *
     * <p>The returned set is the live index set (not a defensive
     * snapshot) — callers must not mutate it. {@link Set#contains} and
     * iteration are safe under the indexer's concurrent writes because
     * the underlying map is a {@link ConcurrentHashMap}.
     */
    public Set<String> subtypesOf(String parentType) {
        if (parentType == null || parentType.isEmpty()) return Set.of();
        Set<String> direct = typeChildren.get(parentType);
        if (direct == null || direct.isEmpty()) return Set.of();
        return direct;
    }

    /**
     * Returns the set of type FQNs that {@code childType} directly
     * extends or implements. The result includes only types that were
     * recorded by the indexer — if the type has no recorded supertypes,
     * this returns an empty set (not null).
     *
     * <p>This is the upward-walk counterpart to {@link #subtypesOf}:
     * {@code resolveTarget} / {@code findInvokedKeys} use it to find the
     * declaration or call edges of a method the user's class inherits
     * from a supertype. Walking down into subtypes would resolve a query
     * to a method the user's class does not actually have.
     *
     * <p>The returned set is the live index set (not a defensive
     * snapshot) — callers must not mutate it.
     */
    public Set<String> supertypesOf(String childType) {
        if (childType == null || childType.isEmpty()) return Set.of();
        Set<String> direct = typeParents.get(childType);
        if (direct == null || direct.isEmpty()) return Set.of();
        return direct;
    }

    /**
     * Returns the set of MethodKeys that share {@code m}'s method-hierarchy
     * group, including {@code m} itself. Iteration order is sorted by
     * {@link MethodKey#toString()} for deterministic BFS expansion.
     *
     * <p>Returns a singleton {@code Set.of(m)} if {@code m} has no
     * hierarchy entries (the common case for plain class methods that
     * don't override anything).
     */
    public Set<MethodKey> relatedMethods(MethodKey m) {
        if (m == null) return Set.of();
        Set<MethodKey> group = hierarchy.get(m);
        if (group == null || group.isEmpty()) {
            return Set.of(m);
        }
        java.util.TreeSet<MethodKey> out = new java.util.TreeSet<>(
                (a, b) -> a.toString().compareTo(b.toString()));
        out.add(m);
        out.addAll(group);
        return out;
    }

    /**
     * Records one symbol (type, method, constructor, or field) in the
     * symbol table, keyed by its lowercase simple name. Duplicate
     * (name, kind, fqn) entries are coalesced — the indexer may visit the
     * same declaration more than once in pathological cases (e.g. an
     * ImplicitTypeDeclaration wrapping a top-level record in JDT 3.45)
     * and we don't want a noisy result. Different FQNs under the same
     * simple name (a common case, e.g. {@code equals} in many classes)
     * are kept distinct.
     */
    public void recordSymbol(Symbol s) {
        if (s == null || s.name == null || s.name.isEmpty()) return;
        // Locale.ROOT: the bucket key and the search needle must fold the
        // same way regardless of the host default locale (Turkish-I etc.).
        List<Symbol> bucket = symbols.computeIfAbsent(s.name.toLowerCase(Locale.ROOT),
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (bucket) {
            for (Symbol existing : bucket) {
                if (existing.kind.equals(s.kind)
                        && existing.fqn.equals(s.fqn)
                        && java.util.Objects.equals(existing.signature, s.signature)) {
                    return;  // duplicate, skip
                }
            }
            bucket.add(s);
        }
    }

    /**
     * How a symbol matched the query, and the text that matched. Issue #9:
     * {@code find_symbols} used to return a bare symbol list, so a
     * case-insensitive or fuzzy hit gave no hint of where the query
     * differed from the name — the caller had to re-derive it.
     *
     * <ul>
     *   <li>{@link #EXACT} — {@code name.equals(query)}.</li>
     *   <li>{@link #CASE_INSENSITIVE} — equal ignoring case, but not equal.</li>
     *   <li>{@link #SUBSTRING} — case-insensitive substring (the historical
     *       behavior); {@code matchedText} is the substring of the name
     *       that matched, in the name's own casing.</li>
     *   <li>{@link #FUZZY} — Levenshtein distance within the threshold;
     *       only produced when the caller opts in. {@code distance} is the
     *       edit distance.</li>
     * </ul>
     */
    public enum MatchKind { EXACT, CASE_INSENSITIVE, SUBSTRING, FUZZY }

    /**
     * One {@code find_symbols} hit: the symbol plus the match annotation.
     *
     * @param symbol      the matched symbol
     * @param kind        how it matched
     * @param matchedText the part of the symbol name that matched (its own
     *                    casing preserved), or the whole name for
     *                    {@link MatchKind#EXACT}, {@link MatchKind#CASE_INSENSITIVE}
     *                    and {@link MatchKind#FUZZY}
     * @param distance    Levenshtein distance for {@link MatchKind#FUZZY},
     *                    0 otherwise
     */
    public record SymbolMatch(Symbol symbol, MatchKind kind,
                              String matchedText, int distance) {}

    /**
     * Result of a {@link #searchSymbols} call: the sorted, capped list of
     * matches plus the total number of matches that existed before the
     * cap was applied. {@code matches.size()} may be less than
     * {@code totalCount} when the caller passed a finite {@code limit}
     * — callers use {@code totalCount > matches.size()} as the
     * "capped/truncated" signal without a second pass.
     */
    public record SymbolMatchResult(List<SymbolMatch> matches, int totalCount) {}

    /**
     * Returns every recorded symbol whose simple name contains
     * {@code query} (case-insensitive substring match), optionally
     * filtered to a single kind. Results are sorted by (fqn, signature)
     * for stable output. {@code limit} caps the number of returned
     * symbols; pass {@link Integer#MAX_VALUE} for "no cap" (caller's
     * responsibility). The returned {@link SymbolMatchResult#totalCount}
     * is the match count *before* the cap is applied, so a caller that
     * needs to know whether the result was truncated can do so without a
     * second scan over the index.
     *
     * <p>All matches are collected and sorted, then truncated to
     * {@code limit} — so the returned prefix is the first {@code limit}
     * entries in sort order, not an arbitrary slice of the map's
     * iteration order. {@code O(N log N)} in the match count; the
     * alternative (keep the first {@code limit} seen, then sort) is
     * cheaper but returns a non-deterministic subset, which callers
     * cannot reason about.
     */
    public SymbolMatchResult searchSymbols(String query, String kindFilter, int limit) {
        return searchSymbols(query, kindFilter, limit, false);
    }

    /**
     * As {@link #searchSymbols(String, String, int)}, with an optional
     * typo-tolerant fuzzy pass.
     *
     * @param fuzzy when true, also match names within a Levenshtein
     *              distance of {@link #fuzzyThreshold(String)} (1 edit for
     *              queries up to 7 characters, 2 beyond). Off by default
     *              because it is inherently noisier than substring
     *              matching; the match annotation
     *              ({@link SymbolMatch#kind()}) tells the caller which
     *              hits came from the fuzzy pass.
     */
    public SymbolMatchResult searchSymbols(String query, String kindFilter, int limit,
                                           boolean fuzzy) {
        if (query == null || query.isEmpty()) {
            return new SymbolMatchResult(List.of(), 0);
        }
        String needle = query.toLowerCase(Locale.ROOT);
        int maxDistance = fuzzyThreshold(query);
        List<SymbolMatch> hits = new ArrayList<>();
        for (List<Symbol> bucket : symbols.values()) {
            List<Symbol> snap;
            synchronized (bucket) {
                snap = List.copyOf(bucket);
            }
            for (Symbol s : snap) {
                if (kindFilter != null && !kindFilter.equals(s.kind)) continue;
                SymbolMatch m = matchSymbol(s, query, needle, fuzzy, maxDistance);
                if (m != null) hits.add(m);
            }
        }
        hits.sort((a, b) -> {
            int byFqn = a.symbol.fqn.compareTo(b.symbol.fqn);
            if (byFqn != 0) return byFqn;
            String sa = a.symbol.signature == null ? "" : a.symbol.signature;
            String sb = b.symbol.signature == null ? "" : b.symbol.signature;
            return sa.compareTo(sb);
        });
        int total = hits.size();
        // A negative limit is a caller bug; preserve the historical
        // contract of an empty match list (with the full total) rather
        // than throwing from subList.
        int effectiveLimit = Math.max(0, limit);
        if (hits.size() > effectiveLimit) {
            hits = new ArrayList<>(hits.subList(0, effectiveLimit));
        }
        return new SymbolMatchResult(hits, total);
    }

    /**
     * Matches one symbol against the query, returning the annotated hit or
     * null. Order matters: exact, then case-insensitive equality, then
     * substring, then (opt-in) fuzzy — the first that applies wins, so a
     * name that is both an exact match and a substring is reported as
     * {@link MatchKind#EXACT}.
     */
    private static SymbolMatch matchSymbol(Symbol s, String query, String needle,
                                           boolean fuzzy, int maxDistance) {
        String name = s.name;
        if (name.equals(query)) {
            return new SymbolMatch(s, MatchKind.EXACT, name, 0);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(needle)) {
            return new SymbolMatch(s, MatchKind.CASE_INSENSITIVE, name, 0);
        }
        int idx = lower.indexOf(needle);
        if (idx >= 0) {
            // Lowercasing can change length for a few Unicode letters
            // (e.g. U+0130); only slice when the fold is length-preserving,
            // otherwise report the whole name.
            String matched = lower.length() == name.length()
                    ? name.substring(idx, idx + needle.length())
                    : name;
            return new SymbolMatch(s, MatchKind.SUBSTRING, matched, 0);
        }
        if (fuzzy && Math.abs(name.length() - query.length()) <= maxDistance) {
            int distance = levenshtein(lower, needle);
            if (distance <= maxDistance) {
                return new SymbolMatch(s, MatchKind.FUZZY, name, distance);
            }
        }
        return null;
    }

    /**
     * Fuzzy distance threshold for a query: 1 edit for queries up to 7
     * characters, 2 edits beyond that. Short names are too easy to
     * confusable to allow more; long names tolerate the extra edit.
     */
    private static int fuzzyThreshold(String query) {
        return query.length() <= 7 ? 1 : 2;
    }

    /**
     * Levenshtein edit distance between two strings, O(n·m) time and
     * O(min(n,m)) space via a two-row dynamic program. Both inputs are
     * already lowercased by the caller.
     */
    private static int levenshtein(String a, String b) {
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }

    public List<MethodKey> callersOf(MethodKey target) {
        Set<MethodKey> set = calls.get(target);
        if (set == null) return Collections.emptyList();
        synchronized (set) {
            return List.copyOf(set);
        }
    }

    /**
     * Returns the raw set of callers for {@code target} without copying.
     *
     * <p>Intended for single-threaded BFS in {@link CallChainAnalyzer}
     * after indexing is complete — the returned set is the live index
     * entry, not a defensive copy. NOT a substitute for
     * {@link #callersOf} in concurrent contexts: callers that mutate
     * the returned set will corrupt the index. The BFS path is safe
     * because {@code recordInvocation} only runs during
     * {@link JdtIndexer#parseFile} on virtual threads, and the BFS
     * entry point in {@code TraceCallersService.traceCallersJson}
     * runs serially after indexing finishes.
     */
    public Set<MethodKey> callersOfSet(MethodKey target) {
        Set<MethodKey> set = calls.get(target);
        return set == null ? Set.of() : set;
    }

    /**
     * Returns the methods {@code caller} directly invokes (the forward
     * direction of {@link #recordInvocation}), or an empty set if none.
     * Used by the downward callee tree ({@code trace_callees}, issue #9).
     * The returned set is a live index entry — callers must not mutate it,
     * same contract as {@link #callersOfSet}.
     */
    public Set<MethodKey> invokesOf(MethodKey caller) {
        Set<MethodKey> set = invokes.get(caller);
        return set == null ? Set.of() : set;
    }

    /**
     * Defensive snapshot of {@link #invokesOf(MethodKey)}.
     */
    public List<MethodKey> invokesOfList(MethodKey caller) {
        Set<MethodKey> set = invokes.get(caller);
        if (set == null) return Collections.emptyList();
        synchronized (set) {
            return List.copyOf(set);
        }
    }

    /**
     * Returns a defensive snapshot of call sites for {@code target}, keyed
     * by the caller MethodKey. Each value is the ordered list of call
     * sites within that caller's body (one entry per AST node that
     * resolves to {@code target}). Returns an empty map if {@code target}
     * has no recorded call sites.
     */
    public Map<MethodKey, List<CallSite>> callSitesOf(MethodKey target) {
        Map<MethodKey, List<CallSite>> m = callSites.get(target);
        if (m == null) return Collections.emptyMap();
        Map<MethodKey, List<CallSite>> snap = new java.util.LinkedHashMap<>(m.size());
        for (Map.Entry<MethodKey, List<CallSite>> e : m.entrySet()) {
            synchronized (e.getValue()) {
                snap.put(e.getKey(), List.copyOf(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(snap);
    }

    /**
     * Returns the raw call-site map for {@code target} without copying,
     * mirroring {@link #callersOfSet}. Intended for the single-threaded
     * read path (the BFS in {@link CallChainAnalyzer} and the service
     * layer) after indexing is complete. Callers must not mutate the
     * returned map or its lists.
     *
     * <p>Unlike {@link #callersOfSet}, which dedupes to the caller set,
     * this exposes each call site's {@link CallSite#receiverType()} —
     * required for the issue #7 / #8 receiver-static-type filter, which
     * is per expression, not per caller.
     */
    public Map<MethodKey, List<CallSite>> callSitesOfSet(MethodKey target) {
        Map<MethodKey, List<CallSite>> m = callSites.get(target);
        return m == null ? Map.of() : m;
    }

    /**
     * True if {@code subType} is {@code superType} or a (transitive)
     * subtype of it, according to the project type hierarchy recorded by
     * {@link #recordTypeHierarchy}. Used by the caller BFS to filter
     * hierarchy expansion by receiver static type (issues #7 / #8):
     * a call site on a receiver of static type {@code R} can only
     * dispatch to a method declared in {@code R} or a subtype of {@code R}.
     *
     * <p><b>Conservative on unknowns:</b> a null {@code superType} (the
     * receiver type could not be resolved at index time) or a null
     * {@code subType} returns {@code true} — an unknown fact must not
     * filter a real call site out. A non-null {@code superType} that is
     * simply not reachable from {@code subType} through the recorded
     * hierarchy returns {@code false}.
     *
     * <p>Only project-source type edges are recorded (library supertypes
     * are skipped by the indexer), so a project type that extends a
     * library type will not be reported as a subtype of that library
     * type. That case cannot arise for the filter's intended use: the
     * filter only runs when the callee is a project method, and a project
     * method can only be the resolved callee of a call whose receiver
     * type is the method's own class or a project supertype of it.
     */
    public boolean isSubtypeOf(String subType, String superType) {
        if (subType == null || superType == null) return true;
        if (subType.equals(superType)) return true;
        Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.addLast(subType);
        visited.add(subType);
        while (!queue.isEmpty()) {
            String cls = queue.removeFirst();
            for (String parent : supertypesOf(cls)) {
                if (parent.equals(superType)) return true;
                if (visited.add(parent)) queue.addLast(parent);
            }
        }
        return false;
    }

    public SourceLoc declarationOf(MethodKey method) {
        return declarations.get(method);
    }

    public Set<MethodKey> knownMethods() {
        return Collections.unmodifiableSet(declarations.keySet());
    }

    /**
     * Returns a defensive snapshot of declared MethodKeys whose
     * {@code methodName} and {@code arity} match the given signature,
     * across all declaring classes and overloads in the project.
     * Returns an empty set if no project declaration matches or
     * {@code methodName} is null.
     *
     * <p>The snapshot is decoupled from the index, so callers can
     * iterate freely without seeing concurrent writes from the
     * indexer. A single declaring class may have multiple Java
     * overloads sharing the same (name, arity) — all are returned.
     * For "give me the method in this specific class", use
     * {@link #methodInClassWithSignature} or
     * {@link #methodsInClassWithSignature} instead — they avoid the
     * bucket scan this method requires.
     */
    public Set<MethodKey> methodsWithSignature(String methodName, int arity) {
        if (methodName == null) return Set.of();
        Map<String, Set<MethodKey>> bucket = bySignature.get(new NameArity(methodName, arity));
        if (bucket == null) return Set.of();
        Set<MethodKey> all = new java.util.HashSet<>();
        for (Set<MethodKey> overloads : bucket.values()) {
            all.addAll(overloads);
        }
        return Set.copyOf(all);
    }

    /**
     * Returns the single declared MethodKey in {@code className} with
     * the given signature, or null if there is no such declaration.
     * Equivalent to filtering {@link #methodsWithSignature} by
     * declaring class, but a single {@link ConcurrentHashMap#get(Object)}
     * instead of a full signature-bucket scan — important for the
     * post-build reverse-hierarchy repair pass, where each subtype
     * visit would otherwise re-iterate the entire signature set (e.g.
     * all {@code equals(Object)} or {@code hashCode()} declarations
     * across the project, which is the build-phase bottleneck for
     * large projects).
     *
     * <p>When the target class has multiple Java overloads sharing the
     * same (name, arity) — for example {@code save(String)} and
     * {@code save(int)} — this method throws
     * {@link AmbiguousMethodException} rather than silently choosing
     * one. Callers that need every overload should use
     * {@link #methodsInClassWithSignature} instead.
     */
    public MethodKey methodInClassWithSignature(String className, String methodName, int arity)
            throws AmbiguousMethodException {
        if (className == null || methodName == null) return null;
        Map<String, Set<MethodKey>> bucket = bySignature.get(new NameArity(methodName, arity));
        if (bucket == null) return null;
        Set<MethodKey> overloads = bucket.get(className);
        if (overloads == null || overloads.isEmpty()) return null;
        if (overloads.size() > 1) {
            throw new AmbiguousMethodException(className, methodName, arity, null);
        }
        return overloads.iterator().next();
    }

    /**
     * Returns every declared MethodKey in {@code className} sharing
     * the given signature, or an empty set if none. Use this instead
     * of {@link #methodInClassWithSignature} when the class may have
     * multiple overloads of the same (name, arity) and the caller
     * wants each one — for example, the reverse-hierarchy repair pass
     * needs every {@code save(String)}, {@code save(int)}, and any
     * other overload in a subtype to be linked as a hierarchy edge
     * to the parent method.
     *
     * <p>The returned set is a defensive snapshot decoupled from the
     * index, so callers may iterate freely without seeing concurrent
     * writes from the indexer.
     */
    public Set<MethodKey> methodsInClassWithSignature(String className, String methodName, int arity) {
        if (className == null || methodName == null) return Set.of();
        Map<String, Set<MethodKey>> bucket = bySignature.get(new NameArity(methodName, arity));
        if (bucket == null) return Set.of();
        Set<MethodKey> overloads = bucket.get(className);
        if (overloads == null || overloads.isEmpty()) return Set.of();
        return Set.copyOf(overloads);
    }

    /**
     * Returns an immutable snapshot of the reverse call index. Each inner
     * set is a defensive copy: callers can iterate without seeing concurrent
     * mutations to {@link #recordInvocation} updates, and they cannot
     * accidentally reach into a still-live {@link LinkedHashSet} and
     * corrupt the indexer's data structure.
     */
    public Map<MethodKey, Set<MethodKey>> allCalls() {
        Map<MethodKey, Set<MethodKey>> snap = new java.util.LinkedHashMap<>(calls.size());
        for (Map.Entry<MethodKey, Set<MethodKey>> e : calls.entrySet()) {
            synchronized (e.getValue()) {
                snap.put(e.getKey(), Set.copyOf(e.getValue()));
            }
        }
        return Collections.unmodifiableMap(snap);
    }

    /**
     * Resolve (class, name) to a declared method, with optional arity and parameter types
     * for overload disambiguation.
     *
     * @return the unique matching method, or null if none
     * @throws AmbiguousMethodException if more than one method matches the selector
     */
    public MethodKey resolveTarget(String className, String methodName) throws AmbiguousMethodException {
        return resolveTarget(className, methodName, null, null);
    }

    public MethodKey resolveTarget(String className, String methodName, Integer arity)
            throws AmbiguousMethodException {
        return resolveTarget(className, methodName, arity, null);
    }

    public MethodKey resolveTarget(String className, String methodName,
                                   Integer arity, List<String> paramTypes)
            throws AmbiguousMethodException {
        // Strict match plus FQN-suffix fallback, scoped to the
        // caller's class. The helper handles the suffix fallback
        // internally (iff strict added nothing AND paramTypes != null).
        // Ambiguity is surfaced: if two declarations on the same
        // class both match, throw.
        List<MethodKey> matches = matchStrictThenSuffix(
                declarations.keySet(), className, methodName, arity, paramTypes);
        if (matches.size() > 1) {
            throw new AmbiguousMethodException(className, methodName, arity, paramTypes);
        }
        if (!matches.isEmpty()) return matches.get(0);

        // Ancestor-walk fallback. The user passed a class or
        // interface that does NOT itself declare the target method —
        // it inherits the method from a supertype (e.g. ISub extends
        // IBase; ISub has no `InvFundTicketPrint` declaration, only
        // IBase does). Calls through an ISub-typed field bind to
        // IBase#m in JDT, so the calls map and declarations both
        // reference IBase. We walk up the type hierarchy from
        // `className` (via supertypesOf, which returns only direct
        // supertypes), and for each ancestor try the strict +
        // FQN-suffix match. First match wins; multiple distinct
        // ancestors declaring the same name+arity is still a real
        // diamond (a class implementing two interfaces that both
        // declare m) — we return the first to keep the BFS coherent
        // and surface any ambiguity via AmbiguousMethodException if
        // two candidates collide on the same ancestor walk.
        return resolveTargetViaAncestors(className, methodName, arity, paramTypes);
    }

    /**
     * Walks the type hierarchy upward from {@code startClass} (each step
     * consults {@link #supertypesOf} for the next ancestor set) and tries
     * to resolve {@code methodName} against each ancestor's declarations.
     * Returns the first match — strict match preferred, FQN-suffix match
     * used only when strict fails for that ancestor. Ambiguity is
     * surfaced the same way {@link #resolveTarget(String, String, Integer, java.util.List)}
     * does: if two declarations on the same ancestor both match, throw.
     *
     * <p>Bounded by the size of the type hierarchy plus a visited-set
     * guard against cycles (a misconfigured index could create them). In
     * practice type hierarchies are shallow (1–5 levels) and diamond-free
     * for project source, so the walk is a few iterations.
     */
    private MethodKey resolveTargetViaAncestors(String startClass, String methodName,
                                                 Integer arity, List<String> paramTypes)
            throws AmbiguousMethodException {
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        queue.addLast(startClass);
        visited.add(startClass);
        while (!queue.isEmpty()) {
            String cls = queue.removeFirst();
            // Per-ancestor strict + FQN-suffix match. The helper's
            // internal strict.isEmpty() check is what gives us the
            // per-iteration semantics (a strict hit on one ancestor
            // does not suppress the suffix pass on the next).
            List<MethodKey> hits = matchStrictThenSuffix(
                    declarations.keySet(), cls, methodName, arity, paramTypes);
            if (hits.size() > 1) {
                throw new AmbiguousMethodException(startClass, methodName, arity, paramTypes);
            }
            if (!hits.isEmpty()) return hits.get(0);
            // Walk one level up.
            Set<String> parents = supertypesOf(cls);
            if (parents != null) {
                for (String p : parents) {
                    if (visited.add(p)) queue.addLast(p);
                }
            }
        }
        return null;
    }

    /**
     * Lenient paramType matcher: a user paramType {@code u} matches a
     * declared paramType {@code d} if either equals, or both have the
     * same last {@code .}-delimited segment (the simple class name).
     * E.g. user {@code "DTO"} matches declared {@code "com.example.dto.DTO"};
     * user {@code "java.util.List"} matches declared
     * {@code "java.util.List<java.lang.String>"} (no dot in declared,
     * last segment is the whole token — equal to user's first segment).
     */
    private static boolean paramTypesMatchBySuffix(List<String> user, List<String> declared) {
        for (int i = 0; i < user.size(); i++) {
            String u = user.get(i);
            String d = declared.get(i);
            if (u == null || d == null) return false;
            if (u.equals(d)) continue;
            int dotU = u.lastIndexOf('.');
            int dotD = d.lastIndexOf('.');
            String tailU = dotU < 0 ? u : u.substring(dotU + 1);
            String tailD = dotD < 0 ? d : d.substring(dotD + 1);
            if (!tailU.equals(tailD)) return false;
        }
        return true;
    }

    /**
     * Strict match plus FQN-suffix fallback against a single iteration's
     * candidates. Iterates {@code candidates} with strict equality first;
     * iff the strict pass added nothing AND {@code paramTypes != null},
     * runs a second pass with FQN-suffix matching. Returns the union
     * (strict matches if any, else suffix matches).
     *
     * <p>The per-iteration semantic is the load-bearing part: a strict
     * hit on iteration 1 must NOT suppress the suffix pass on iteration
     * 2 (e.g. when each iteration scans declarations for a different
     * ancestor in the type hierarchy walk). Callers run one invocation
     * per iteration so the fallback is naturally per-iteration.
     */
    static List<MethodKey> matchStrictThenSuffix(
            Iterable<MethodKey> candidates, String cls, String methodName,
            Integer arity, List<String> paramTypes) {
        List<MethodKey> strict = new ArrayList<>();
        for (MethodKey k : candidates) {
            if (!k.declaringClass.equals(cls) || !k.methodName.equals(methodName)) continue;
            if (arity != null && k.arity != arity.intValue()) continue;
            if (paramTypes != null && !paramTypes.equals(k.parameterTypes)) continue;
            strict.add(k);
        }
        if (!strict.isEmpty() || paramTypes == null) return strict;
        List<MethodKey> suffix = new ArrayList<>();
        for (MethodKey k : candidates) {
            if (!k.declaringClass.equals(cls) || !k.methodName.equals(methodName)) continue;
            if (arity != null && k.arity != arity.intValue()) continue;
            if (k.parameterTypes.size() != paramTypes.size()) continue;
            if (!paramTypesMatchBySuffix(paramTypes, k.parameterTypes)) continue;
            suffix.add(k);
        }
        return suffix;
    }

    /** Lists every declared method with the given name, regardless of arity. */
    public List<MethodKey> findOverloads(String className, String methodName) {
        List<MethodKey> out = new ArrayList<>();
        for (MethodKey m : declarations.keySet()) {
            if (m.declaringClass.equals(className) && m.methodName.equals(methodName)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Lists every declared method with the given name visible from
     * {@code className}, including inherited ones (walks the ancestor
     * hierarchy). Used for ambiguity diagnostics where the overloads may
     * be declared on a supertype rather than the queried class itself —
     * e.g. {@code Child extends Parent} with two {@code Parent#foo}
     * overloads: the ambiguity is thrown from the ancestor walk, but the
     * overloads live on {@code Parent}, not {@code Child}.
     */
    public List<MethodKey> findOverloadsInHierarchy(String className, String methodName) {
        List<MethodKey> out = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.addLast(className);
        visited.add(className);
        while (!queue.isEmpty()) {
            String cls = queue.removeFirst();
            for (MethodKey m : declarations.keySet()) {
                if (m.declaringClass.equals(cls) && m.methodName.equals(methodName)) {
                    out.add(m);
                }
            }
            Set<String> parents = supertypesOf(cls);
            if (parents != null) {
                for (String p : parents) {
                    if (visited.add(p)) queue.addLast(p);
                }
            }
        }
        return out;
    }

    /**
     * Find every method KEY in the call-edge map (i.e. methods that this
     * project's sources invoke) matching {@code className} and
     * {@code methodName}, optionally narrowed by {@code arity} and
     * {@code paramTypes}. Used to resolve library-method targets: those
     * methods have no project declaration, so {@link #resolveTarget} sees
     * nothing, but {@link JdtIndexer} still recorded the call edges with
     * the exact signature from the JDT binding (e.g.
     * {@code java.io.PrintStream#println/1(java.lang.String)}). The
     * caller can then union {@link #callersOf} across the returned keys.
     *
     * <p>Returns an empty list if nothing matches. The order is
     * undefined.
     */
    public List<MethodKey> findInvokedKeys(String className, String methodName,
                                           Integer arity, List<String> paramTypes) {
        List<MethodKey> out = new ArrayList<>();
        // Strict match plus FQN-suffix fallback, scoped to the
        // caller's class. The helper handles the suffix fallback
        // internally (iff strict added nothing AND paramTypes != null).
        List<MethodKey> hits = matchStrictThenSuffix(
                calls.keySet(), className, methodName, arity, paramTypes);
        out.addAll(hits);
        // Ancestor-walk pass (issue #3 sub-interface scenario): the
        // user's className is a sub-interface (e.g. ISub) that
        // inherits the method from a supertype (e.g. IBase), so no
        // call edge was ever recorded under ISub. JDT binds the call
        // to IBase#m and the calls map stores it under IBase#m. Walk
        // the type hierarchy upward from `className` and gather every
        // matching key. Each ancestor can contribute multiple keys
        // (different overloads / parameterizations) — union them all.
        if (out.isEmpty()) {
            java.util.Deque<String> queue = new java.util.ArrayDeque<>();
            java.util.Set<String> visited = new java.util.HashSet<>();
            queue.addLast(className);
            visited.add(className);
            while (!queue.isEmpty()) {
                String cls = queue.removeFirst();
                // Per-ancestor strict + FQN-suffix match. The helper's
                // internal strict.isEmpty() check is what gives us the
                // per-iteration semantics (a strict hit on one ancestor
                // does not suppress the suffix pass on the next — the
                // regression targeted by
                // ProjectIndexFindInvokedKeysTest).
                List<MethodKey> ancestorHits = matchStrictThenSuffix(
                        calls.keySet(), cls, methodName, arity, paramTypes);
                out.addAll(ancestorHits);
                Set<String> parents = supertypesOf(cls);
                if (parents != null) {
                    for (String p : parents) {
                        if (visited.add(p)) queue.addLast(p);
                    }
                }
            }
        }
        return out;
    }

    public static final class AmbiguousMethodException extends Exception {
        public AmbiguousMethodException(String className, String methodName,
                                        Integer arity, List<String> paramTypes) {
            super(buildMessage(className, methodName, arity, paramTypes));
        }
        private static String buildMessage(String className, String methodName,
                                           Integer arity, List<String> paramTypes) {
            if (paramTypes != null) {
                return "Multiple methods named '" + methodName + "' with parameter types "
                        + paramTypes + " in " + className;
            }
            if (arity != null) {
                return "Multiple methods named '" + methodName + "' with arity " + arity
                        + " in " + className + "; pass `paramTypes` to disambiguate.";
            }
            return "Multiple methods named '" + methodName + "' in " + className
                    + "; pass `arity` (and optionally `paramTypes`) to disambiguate.";
        }
    }
}
