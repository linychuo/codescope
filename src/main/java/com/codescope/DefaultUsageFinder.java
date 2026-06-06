package com.codescope;

import org.eclipse.jdt.core.dom.*;

import java.nio.file.*;
import java.util.*;

/**
 * Default implementation of UsageFinder.
 * Walks every CompilationUnit in the project with a single ASTVisitor pass
 * and populates three reverse indices keyed by symbol.
 */
public class DefaultUsageFinder implements UsageFinder {

    private final List<ProjectModel> models;

    private final Map<String, List<UsageLocation>> methodUsages = new HashMap<>();
    private final Map<String, List<UsageLocation>> fieldUsages = new HashMap<>();
    private final Map<String, List<UsageLocation>> classUsages = new HashMap<>();

    // Project-wide set of class names (simple + FQ) for fallback class-reference
    // detection when binding resolution is unavailable (no classpath).
    private final Set<String> knownClassNames = new HashSet<>();

    // Project-wide set of field names so SimpleName / QualifiedName references
    // can be classified as field reads when binding resolution fails. Without
    // bindings we can't disambiguate which class a field belongs to, so the
    // index is keyed by bare name and lookups match via `k.equals(memberName)`.
    private final Set<String> knownFieldNames = new HashSet<>();

    public DefaultUsageFinder(AnalysisEngine engine) {
        this.models = engine.getModels();
        build();
    }

    @Override
    public List<UsageLocation> findUsages(Symbol symbol) {
        Map<String, List<UsageLocation>> index = switch (symbol.kind) {
            case METHOD -> methodUsages;
            case FIELD -> fieldUsages;
            case CLASS -> classUsages;
        };
        List<UsageLocation> result = new ArrayList<>();
        String exact = symbol.key();
        result.addAll(index.getOrDefault(exact, Collections.emptyList()));
        // Derive the simple form of symbol.className so we can match index keys
        // that were emitted with simple names (the common case when bindings
        // resolve to e.g. `Foo` rather than `pkg.Foo`).
        int lastDot = symbol.className.lastIndexOf('.');
        String simpleClass = lastDot >= 0 ? symbol.className.substring(lastDot + 1) : symbol.className;
        for (Map.Entry<String, List<UsageLocation>> entry : index.entrySet()) {
            String k = entry.getKey();
            if (k.equals(exact)) continue;
            if (symbol.memberName == null) {
                // Class lookup: match FQ or simple form, in either direction
                if (k.equals(symbol.className) || k.endsWith("." + symbol.className)
                    || k.equals(simpleClass) || k.endsWith("." + simpleClass)) {
                    result.addAll(entry.getValue());
                }
            } else {
                // Member lookup: prefer `Class#member` match (FQ or simple),
                // fall back to bare member name when binding info is absent.
                if (k.equals(simpleClass + "#" + symbol.memberName)
                    || k.equals(symbol.className + "#" + symbol.memberName)
                    || k.endsWith("." + simpleClass + "#" + symbol.memberName)
                    || k.equals(symbol.memberName)) {
                    result.addAll(entry.getValue());
                }
            }
        }
        Collections.sort(result);
        return result;
    }

    private void build() {
        // First pass: collect class names so SimpleName-based class references
        // can be identified even when binding resolution is unavailable.
        for (ProjectModel model : models) {
            List<Path> files = new ArrayList<>(model.getFiles());
            for (Path file : files) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;
                collectClassNames(cu);
            }
        }
        // Second pass: emit usages.
        for (ProjectModel model : models) {
            List<Path> files = new ArrayList<>(model.getFiles());
            for (Path file : files) {
                CompilationUnit cu = model.getAst(file);
                if (cu == null) continue;
                cu.accept(new UsageVisitor(file, cu));
            }
        }
    }

    private void collectClassNames(CompilationUnit cu) {
        cu.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration n) {
                knownClassNames.add(n.getName().getIdentifier());
                ITypeBinding b = n.resolveBinding();
                if (b != null && b.getQualifiedName() != null) {
                    knownClassNames.add(b.getQualifiedName());
                }
                return true;
            }
            @Override
            public boolean visit(EnumDeclaration n) {
                knownClassNames.add(n.getName().getIdentifier());
                return true;
            }
            @Override
            public boolean visit(RecordDeclaration n) {
                knownClassNames.add(n.getName().getIdentifier());
                return true;
            }
            @Override
            public boolean visit(AnnotationTypeDeclaration n) {
                knownClassNames.add(n.getName().getIdentifier());
                return true;
            }
            @Override
            public boolean visit(FieldDeclaration n) {
                for (Object frag : n.fragments()) {
                    if (frag instanceof VariableDeclarationFragment v) {
                        knownFieldNames.add(v.getName().getIdentifier());
                    }
                }
                return false;
            }
        });
    }

    /** Emits a usage entry. The visitor's `file` and `cu` are captured via the closure. */
    private void emit(UsageVisitor v, String key, ASTNode node, String snippet, UsageFinder.RefKind kind) {
        if (key == null || key.isEmpty()) return;
        Map<String, List<UsageLocation>> index = switch (kind) {
            case CALL, NEW -> methodUsages;
            case FIELD_READ, FIELD_WRITE -> fieldUsages;
            default -> classUsages;
        };
        int line = v.cu.getLineNumber(node.getStartPosition());
        int col = v.cu.getColumnNumber(node.getStartPosition()) + 1;
        if (line <= 0) line = 1;
        if (col <= 0) col = 1;
        String container = findContainer(node);
        UsageLocation loc = new UsageLocation(v.file, line, col, kind, snippet, container);
        index.computeIfAbsent(key, k -> new ArrayList<>()).add(loc);
        if (kind == UsageFinder.RefKind.NEW) {
            classUsages.computeIfAbsent(key, k -> new ArrayList<>()).add(loc);
        }
    }

    private static String findContainer(ASTNode node) {
        ASTNode current = node.getParent();
        String methodName = null;
        String typeName = null;
        while (current != null) {
            if (methodName == null && current instanceof MethodDeclaration md) {
                methodName = md.getName().getIdentifier();
            } else if (methodName == null && current instanceof Initializer) {
                methodName = "<initializer>";
            } else if (typeName == null && current instanceof TypeDeclaration td) {
                typeName = td.getName().getIdentifier();
            } else if (typeName == null && current instanceof EnumDeclaration ed) {
                typeName = ed.getName().getIdentifier();
            } else if (typeName == null && current instanceof RecordDeclaration rd) {
                typeName = rd.getName().getIdentifier();
            } else if (typeName == null && current instanceof AnnotationTypeDeclaration atd) {
                typeName = atd.getName().getIdentifier();
            }
            current = current.getParent();
        }
        if (typeName != null && methodName != null) return typeName + "." + methodName;
        if (typeName != null) return typeName;
        return "<top-level>";
    }

    private static boolean isAssignmentLhs(ASTNode n) {
        ASTNode parent = n.getParent();
        while (parent != null) {
            if (parent instanceof Assignment) {
                ASTNode lhs = ((Assignment) parent).getLeftHandSide();
                while (lhs instanceof ParenthesizedExpression pe) {
                    lhs = pe.getExpression();
                }
                return lhs == n;
            }
            if (parent instanceof VariableDeclarationFragment
                || parent instanceof SingleVariableDeclaration) {
                return false;
            }
            if (parent instanceof Statement) return false;
            if (parent instanceof Expression) {
                parent = parent.getParent();
                continue;
            }
            return false;
        }
        return false;
    }

    /** Emits a TYPE_REF for a Type node, using the binding if available,
     *  otherwise falling back to the project-wide class index. */
    private void emitTypeRef(UsageVisitor v, Type t, String snippetPrefix, UsageFinder.RefKind kind) {
        ITypeBinding tb = t.resolveBinding();
        String key = null;
        if (tb != null) {
            key = tb.getName();
        } else {
            String name = t.toString();
            // Strip generic parameters and qualifiers
            int lt = name.indexOf('<');
            if (lt > 0) name = name.substring(0, lt);
            if (knownClassNames.contains(name)) {
                key = name;
            }
        }
        if (key != null) {
            emit(v, key, t, snippetPrefix + t, kind);
        }
    }

    private static String shortSnippet(ASTNode n) {
        String s = n.toString();
        if (s.length() > 80) s = s.substring(0, 77) + "...";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private class UsageVisitor extends ASTVisitor {

        private final Path file;
        private final CompilationUnit cu;

        UsageVisitor(Path file, CompilationUnit cu) {
            this.file = file;
            this.cu = cu;
        }

        @Override
        public boolean visit(MethodInvocation n) {
            IMethodBinding binding = n.resolveMethodBinding();
            String name = n.getName().getIdentifier();
            String key = (binding != null && binding.getDeclaringClass() != null)
                ? binding.getDeclaringClass().getName() + "#" + binding.getName()
                : name;
            emit(this, key, n, shortSnippet(n), UsageFinder.RefKind.CALL);
            // Manually descend into the receiver and arguments so qualifier
            // references (e.g. `Foo` in `Foo.bar()` or `callSites` in
            // `callSites.put(...)`) and argument expressions are indexed too.
            if (n.getExpression() != null) n.getExpression().accept(this);
            for (Object arg : n.arguments()) {
                if (arg instanceof ASTNode a) a.accept(this);
            }
            for (Object targ : n.typeArguments()) {
                if (targ instanceof ASTNode a) a.accept(this);
            }
            return false;
        }

        @Override
        public boolean visit(SuperMethodInvocation n) {
            IMethodBinding binding = n.resolveMethodBinding();
            String name = n.getName().getIdentifier();
            String key = (binding != null && binding.getDeclaringClass() != null)
                ? binding.getDeclaringClass().getName() + "#" + binding.getName()
                : name;
            emit(this, key, n, "super." + name + "()", UsageFinder.RefKind.CALL);
            for (Object arg : n.arguments()) {
                if (arg instanceof ASTNode a) a.accept(this);
            }
            return false;
        }

        @Override
        public boolean visit(ClassInstanceCreation n) {
            ITypeBinding tb = n.getType().resolveBinding();
            String key = tb != null ? tb.getName() : n.getType().toString();
            emit(this, key, n, "new " + n.getType().toString() + "(...)", UsageFinder.RefKind.NEW);
            for (Object arg : n.arguments()) {
                if (arg instanceof ASTNode a) a.accept(this);
            }
            return false;
        }

        @Override
        public boolean visit(FieldAccess n) {
            IVariableBinding binding = n.resolveFieldBinding();
            String name = n.getName().getIdentifier();
            String key = (binding != null && binding.getDeclaringClass() != null)
                ? binding.getDeclaringClass().getName() + "#" + binding.getName()
                : name;
            UsageFinder.RefKind kind = isAssignmentLhs(n)
                ? UsageFinder.RefKind.FIELD_WRITE
                : UsageFinder.RefKind.FIELD_READ;
            emit(this, key, n, shortSnippet(n), kind);
            if (n.getExpression() != null) n.getExpression().accept(this);
            return false;
        }

        @Override
        public boolean visit(SuperFieldAccess n) {
            IVariableBinding binding = n.resolveFieldBinding();
            String name = n.getName().getIdentifier();
            String key = (binding != null && binding.getDeclaringClass() != null)
                ? binding.getDeclaringClass().getName() + "#" + binding.getName()
                : name;
            UsageFinder.RefKind kind = isAssignmentLhs(n)
                ? UsageFinder.RefKind.FIELD_WRITE
                : UsageFinder.RefKind.FIELD_READ;
            emit(this, key, n, "super." + name, kind);
            return false;
        }

        @Override
        public boolean visit(TypeDeclaration n) {
            if (n.getSuperclassType() != null) {
                emitTypeRef(this, n.getSuperclassType(),
                    "extends ", UsageFinder.RefKind.EXTENDS);
            }
            for (Object iface : n.superInterfaceTypes()) {
                if (iface instanceof Type t) {
                    emitTypeRef(this, t,
                        "implements ", UsageFinder.RefKind.IMPLEMENTS);
                }
            }
            return true;
        }

        @Override
        public boolean visit(EnumDeclaration n) {
            for (Object iface : n.superInterfaceTypes()) {
                if (iface instanceof Type t) {
                    emitTypeRef(this, t,
                        "implements ", UsageFinder.RefKind.IMPLEMENTS);
                }
            }
            return true;
        }

        @Override
        public boolean visit(RecordDeclaration n) {
            for (Object iface : n.superInterfaceTypes()) {
                if (iface instanceof Type t) {
                    emitTypeRef(this, t,
                        "implements ", UsageFinder.RefKind.IMPLEMENTS);
                }
            }
            return true;
        }

        @Override
        public boolean visit(ImportDeclaration n) {
            IBinding b = n.getName().resolveBinding();
            if (b instanceof ITypeBinding tb) {
                emit(this, tb.getName(), n.getName(), "import " + n.getName(), UsageFinder.RefKind.IMPORT);
            } else {
                String name = n.getName().toString();
                int lastDot = name.lastIndexOf('.');
                String simple = lastDot >= 0 ? name.substring(lastDot + 1) : name;
                if (knownClassNames.contains(simple)) {
                    emit(this, simple, n.getName(), "import " + n.getName(), UsageFinder.RefKind.IMPORT);
                }
            }
            return false;
        }

        @Override
        public boolean visit(CastExpression n) {
            emitTypeRef(this, n.getType(),
                "(", UsageFinder.RefKind.CAST);
            return true;
        }

        @Override
        public boolean visit(InstanceofExpression n) {
            emitTypeRef(this, n.getRightOperand(),
                "... instanceof ", UsageFinder.RefKind.INSTANCEOF);
            return true;
        }

        @Override
        public boolean visit(CatchClause n) {
            SingleVariableDeclaration ex = n.getException();
            if (ex != null && ex.getType() != null) {
                emitTypeRef(this, ex.getType(),
                    "catch (", UsageFinder.RefKind.CATCH);
            }
            return true;
        }

        @Override
        public boolean visit(VariableDeclarationStatement n) {
            emitTypeRef(this, n.getType(),
                "", UsageFinder.RefKind.TYPE_REF);
            return true;
        }

        @Override
        public boolean visit(FieldDeclaration n) {
            emitTypeRef(this, n.getType(),
                "", UsageFinder.RefKind.TYPE_REF);
            return true;
        }

        @Override
        public boolean visit(SingleVariableDeclaration n) {
            emitTypeRef(this, n.getType(),
                "", UsageFinder.RefKind.TYPE_REF);
            return true;
        }

        @Override
        public boolean visit(QualifiedName n) {
            IBinding b = n.resolveBinding();
            if (b instanceof IVariableBinding vb && vb.isField()) {
                ITypeBinding dc = vb.getDeclaringClass();
                if (dc != null) {
                    String key = dc.getName() + "#" + vb.getName();
                    emit(this, key, n, n.getName().getIdentifier(),
                        isAssignmentLhs(n) ? UsageFinder.RefKind.FIELD_WRITE : UsageFinder.RefKind.FIELD_READ);
                }
            } else if (b instanceof ITypeBinding tb) {
                emit(this, tb.getName(), n, n.getName().getIdentifier(), UsageFinder.RefKind.TYPE_REF);
            } else if (b == null) {
                // Binding-resolution fallback: classify the right-hand name
                // against the project-wide field / class indexes.
                String simple = n.getName().getIdentifier();
                if (knownFieldNames.contains(simple)) {
                    emit(this, simple, n, simple,
                        isAssignmentLhs(n) ? UsageFinder.RefKind.FIELD_WRITE : UsageFinder.RefKind.FIELD_READ);
                } else if (knownClassNames.contains(simple)) {
                    emit(this, simple, n, simple, UsageFinder.RefKind.TYPE_REF);
                }
            }
            return false;
        }

        @Override
        public boolean visit(SimpleName n) {
            IBinding b = n.resolveBinding();
            ASTNode parent = n.getParent();
            if (parent instanceof MethodDeclaration) return false;
            if (parent instanceof SuperMethodInvocation) return false;
            if (parent instanceof FieldAccess) return false;
            if (parent instanceof SuperFieldAccess) return false;
            if (parent instanceof VariableDeclarationFragment) return false;
            if (parent instanceof SingleVariableDeclaration) return false;
            if (parent instanceof ImportDeclaration) return false;
            if (parent instanceof AbstractTypeDeclaration) return false;
            // Skip names inside Type nodes — those are already emitted via the
            // Type-aware visitors (VariableDeclarationStatement, FieldDeclaration,
            // CastExpression, etc.). Without this, every typed declaration would
            // produce a duplicate TYPE_REF.
            if (parent instanceof Type) return false;
            // MethodInvocation: skip only if this SimpleName IS the method name
            // (not the qualifier expression, e.g. `Foo` in `Foo.bar()`)
            if (parent instanceof MethodInvocation mi && mi.getName() == n) return false;
            if (parent instanceof QualifiedName) return false;

            if (b instanceof IVariableBinding vb && vb.isField()) {
                ITypeBinding dc = vb.getDeclaringClass();
                if (dc != null) {
                    String key = dc.getName() + "#" + vb.getName();
                    emit(this, key, n, n.getIdentifier(),
                        isAssignmentLhs(n) ? UsageFinder.RefKind.FIELD_WRITE : UsageFinder.RefKind.FIELD_READ);
                }
                return false;
            }
            if (b instanceof ITypeBinding tb) {
                emit(this, tb.getName(), n, n.getIdentifier(), UsageFinder.RefKind.TYPE_REF);
                return false;
            }
            // Binding-resolution fallback: classify the SimpleName against the
            // project-wide field and class indexes.
            if (b == null) {
                String name = n.getIdentifier();
                if (knownFieldNames.contains(name)) {
                    emit(this, name, n, n.getIdentifier(),
                        isAssignmentLhs(n) ? UsageFinder.RefKind.FIELD_WRITE : UsageFinder.RefKind.FIELD_READ);
                } else if (knownClassNames.contains(name)) {
                    emit(this, name, n, n.getIdentifier(), UsageFinder.RefKind.TYPE_REF);
                }
            }
            return false;
        }
    }
}
