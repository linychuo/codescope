package com.codescope;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImplicitTypeDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
        String[] encodingNames = null;  // null = platform default encoding

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
                    // Already recorded in index.skippedFiles; skip
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

        String relPath = relativize(src, projectRoot);
        cu.accept(new CallSiteVisitor(index, relPath));
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
            typeStack.push(nameOf(node));
            return true;
        }

        @Override
        public void endVisit(TypeDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(EnumDeclaration node) {
            typeStack.push(nameOf(node));
            return true;
        }

        @Override
        public void endVisit(EnumDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(RecordDeclaration node) {
            typeStack.push(nameOf(node));
            return true;
        }

        @Override
        public void endVisit(RecordDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(AnnotationTypeDeclaration node) {
            typeStack.push(nameOf(node));
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
            typeStack.push(nameOf(node));
            return true;
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
                paramTypes.add(tb != null ? tb.getQualifiedName() : svd.getType().toString());
            }
            MethodKey callerKey = new MethodKey(
                    callerClass, node.getName().getIdentifier(),
                    paramTypes.size(), paramTypes);
            int line = cuLine(node);
            index.putDeclaration(callerKey, new ProjectIndex.SourceLoc(file, line));
            methodStack.push(new MethodContext(callerKey));
            return true;
        }

        @Override
        public void endVisit(MethodDeclaration node) {
            methodStack.pop();
        }

        @Override
        public boolean visit(MethodInvocation node) {
            recordCall(node.resolveMethodBinding());
            return true;
        }

        @Override
        public boolean visit(SuperMethodInvocation node) {
            recordCall(node.resolveMethodBinding());
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            recordCall(node.resolveConstructorBinding());
            return true;
        }

        @Override
        public boolean visit(ConstructorInvocation node) {
            recordCall(node.resolveConstructorBinding());
            return true;
        }

        @Override
        public boolean visit(SuperConstructorInvocation node) {
            recordCall(node.resolveConstructorBinding());
            return true;
        }

        private void recordCall(IMethodBinding binding) {
            if (binding == null) return;
            if (methodStack.isEmpty()) return;
            MethodKey target = methodKeyOf(binding);
            if (target == null) return;
            index.addCall(target, methodStack.peek().key);
        }

        private MethodKey methodKeyOf(IMethodBinding b) {
            ITypeBinding dc = b.getDeclaringClass();
            if (dc == null) return null;
            ITypeBinding[] pts = b.getParameterTypes();
            List<String> paramTypes = new ArrayList<>(pts.length);
            for (ITypeBinding pt : pts) paramTypes.add(pt.getQualifiedName());
            return new MethodKey(dc.getQualifiedName(), b.getName(), pts.length, paramTypes);
        }

        private String currentClass() {
            if (typeStack.isEmpty()) return packageName.isEmpty() ? "<unknown>" : packageName;
            StringBuilder sb = new StringBuilder();
            if (!packageName.isEmpty()) sb.append(packageName).append('.');
            boolean first = true;
            for (String t : typeStack) {
                if (!first) sb.append('$');
                sb.append(t);
                first = false;
            }
            return sb.toString();
        }

        private static int cuLine(ASTNode n) {
            CompilationUnit cu = (CompilationUnit) n.getRoot();
            return cu.getLineNumber(n.getStartPosition());
        }
    }

    private record MethodContext(MethodKey key) {}
}
