package com.codescope;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

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

        for (Path src : sources) {
            char[] content;
            try {
                content = Files.readString(src, StandardCharsets.UTF_8).toCharArray();
            } catch (IOException e) {
                continue;   // skip unreadable files
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
                // bad source, skip
                continue;
            }
            if (cu == null) continue;

            String relPath = relativize(src, projectRoot);
            cu.accept(new CallSiteVisitor(index, relPath));
        }
        return index;
    }

    private static String relativize(Path file, Path root) {
        try {
            if (root == null) return file.toString();
            return root.relativize(file).toString();
        } catch (Exception e) {
            return file.toString();
        }
    }

    /** AST visitor that records method declarations and call edges. */
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
            typeStack.push(node.getName().getFullyQualifiedName());
            return true;
        }

        @Override
        public void endVisit(TypeDeclaration node) {
            typeStack.pop();
        }

        @Override
        public boolean visit(MethodDeclaration node) {
            String callerClass = currentClass();
            MethodKey callerKey = new MethodKey(callerClass, node.getName().getIdentifier(),
                    node.parameters().size());
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
            return new MethodKey(dc.getQualifiedName(), b.getName(), b.getParameterTypes().length);
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
