package com.codescope.index;

import java.util.List;
import java.util.Map;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;

/**
 * Detects framework <em>entry points</em> — the top of a call chain where
 * control enters the application from outside (issue #9 item 3: the tool
 * used to report such methods as ordinary nodes, so a caller chain ended
 * at a Spring handler with no hint that nothing in the project calls it).
 *
 * <p>Detection is annotation-driven, with a {@code main} signature check.
 * Method annotations are read from the AST (so the annotation type binding
 * gives an authoritative FQN); type-level annotations come from the
 * binding model and are matched by simple name. When neither resolves
 * (a dependency missing from the classpath), the written simple name is
 * matched as a fallback.
 *
 * <p>Kept package-private and static: the indexer is the only caller, and
 * the recognized sets are fixed framework contracts rather than
 * configuration.
 */
final class EntryPoints {

    private EntryPoints() {}

    /** Spring Web handler mappings (method or type level). */
    private static final Map<String, String> BY_FQN = Map.ofEntries(
            Map.entry("org.springframework.web.bind.annotation.RequestMapping", "spring-mvc"),
            Map.entry("org.springframework.web.bind.annotation.GetMapping", "spring-mvc"),
            Map.entry("org.springframework.web.bind.annotation.PostMapping", "spring-mvc"),
            Map.entry("org.springframework.web.bind.annotation.PutMapping", "spring-mvc"),
            Map.entry("org.springframework.web.bind.annotation.DeleteMapping", "spring-mvc"),
            Map.entry("org.springframework.web.bind.annotation.PatchMapping", "spring-mvc"),
            Map.entry("org.springframework.scheduling.annotation.Scheduled", "spring-scheduled"),
            Map.entry("org.springframework.context.event.EventListener", "spring-event"),
            Map.entry("org.springframework.kafka.annotation.KafkaListener", "spring-kafka"),
            Map.entry("org.springframework.amqp.rabbit.annotation.RabbitListener", "spring-rabbit"),
            Map.entry("org.springframework.jms.annotation.JmsListener", "spring-jms"),
            Map.entry("org.junit.jupiter.api.Test", "junit5"),
            Map.entry("org.junit.jupiter.params.ParameterizedTest", "junit5"),
            Map.entry("org.junit.jupiter.api.BeforeEach", "junit5-lifecycle"),
            Map.entry("org.junit.jupiter.api.AfterEach", "junit5-lifecycle"),
            Map.entry("org.junit.jupiter.api.BeforeAll", "junit5-lifecycle"),
            Map.entry("org.junit.jupiter.api.AfterAll", "junit5-lifecycle"),
            Map.entry("org.junit.Test", "junit4"),
            Map.entry("org.junit.Before", "junit4-lifecycle"),
            Map.entry("org.junit.After", "junit4-lifecycle"),
            Map.entry("org.junit.BeforeClass", "junit4-lifecycle"),
            Map.entry("org.junit.AfterClass", "junit4-lifecycle"),
            Map.entry("org.testng.annotations.Test", "testng"),
            Map.entry("org.testng.annotations.BeforeMethod", "testng-lifecycle"),
            Map.entry("org.testng.annotations.AfterMethod", "testng-lifecycle"),
            Map.entry("org.testng.annotations.BeforeClass", "testng-lifecycle"),
            Map.entry("org.testng.annotations.AfterClass", "testng-lifecycle"));

    /**
     * Simple-name fallback for the distinctive annotations above, used when
     * the annotation type binding cannot be resolved (e.g. the framework
     * jar is not on the classpath). {@code Test} is deliberately generic
     * because the simple name alone cannot tell JUnit 4 from 5.
     */
    private static final Map<String, String> BY_SIMPLE_NAME = Map.ofEntries(
            Map.entry("RequestMapping", "spring-mvc"),
            Map.entry("GetMapping", "spring-mvc"),
            Map.entry("PostMapping", "spring-mvc"),
            Map.entry("PutMapping", "spring-mvc"),
            Map.entry("DeleteMapping", "spring-mvc"),
            Map.entry("PatchMapping", "spring-mvc"),
            Map.entry("Scheduled", "spring-scheduled"),
            Map.entry("EventListener", "spring-event"),
            Map.entry("KafkaListener", "spring-kafka"),
            Map.entry("RabbitListener", "spring-rabbit"),
            Map.entry("JmsListener", "spring-jms"),
            Map.entry("Test", "junit"),
            Map.entry("ParameterizedTest", "junit5"),
            Map.entry("BeforeEach", "junit5-lifecycle"),
            Map.entry("AfterEach", "junit5-lifecycle"),
            Map.entry("BeforeAll", "junit5-lifecycle"),
            Map.entry("AfterAll", "junit5-lifecycle"));

    /**
     * @param method          the declared method binding
     * @param astModifiers    the method's AST modifiers list (JDT folds
     *                        annotations into it), used for authoritative
     *                        FQN matching
     * @return the entry-point kind (e.g. {@code "spring-mvc"}), or null if
     *         it is an ordinary method
     */
    static String detectKind(IMethodBinding method, List<? extends ASTNode> astModifiers) {
        if (method == null) return null;
        String kind = kindOfAstAnnotations(astModifiers);
        if (kind != null) return kind;
        // Type-level @RequestMapping marks every handler method in the class.
        ITypeBinding declaring = method.getDeclaringClass();
        if (declaring != null) {
            kind = kindOfModelAnnotations(declaring.getAnnotations());
            if (kind != null) return kind;
        }
        if (isMainMethod(method)) return "main";
        return null;
    }

    private static String kindOfAstAnnotations(List<? extends ASTNode> nodes) {
        if (nodes == null) return null;
        for (ASTNode node : nodes) {
            if (!(node instanceof org.eclipse.jdt.core.dom.Annotation a)) continue;
            ITypeBinding tb = null;
            try {
                IAnnotationBinding ab = a.resolveAnnotationBinding();
                if (ab != null) tb = ab.getAnnotationType();
            } catch (RuntimeException e) {
                // Recovery-mode binding resolution can throw; the written
                // name fallback below still applies.
            }
            String fqn = tb == null ? null : tb.getQualifiedName();
            if (fqn != null && !fqn.isEmpty()) {
                String kind = BY_FQN.get(fqn);
                if (kind != null) return kind;
            }
            String written = writtenSimpleName(a);
            if (written != null) {
                String kind = BY_SIMPLE_NAME.get(written);
                if (kind != null) return kind;
            }
        }
        return null;
    }

    private static String kindOfModelAnnotations(IAnnotationBinding[] annotations) {
        if (annotations == null) return null;
        for (IAnnotationBinding ab : annotations) {
            if (ab == null) continue;
            ITypeBinding tb = ab.getAnnotationType();
            String fqn = tb == null ? null : tb.getQualifiedName();
            if (fqn != null && !fqn.isEmpty()) {
                String kind = BY_FQN.get(fqn);
                if (kind != null) return kind;
            }
            String kind = BY_SIMPLE_NAME.get(simpleName(ab.getName()));
            if (kind != null) return kind;
        }
        return null;
    }

    /** The written simple name of an AST annotation, or null if unreadable. */
    private static String writtenSimpleName(org.eclipse.jdt.core.dom.Annotation a) {
        try {
            return simpleName(a.getTypeName().getFullyQualifiedName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String simpleName(String name) {
        if (name == null || name.isEmpty()) return null;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /**
     * {@code public static void main(String[])} — the JVM entry point.
     * Varargs {@code main(String...)} is the same signature after erasure.
     */
    private static boolean isMainMethod(IMethodBinding m) {
        if (!m.getName().equals("main")) return false;
        if (!Modifier.isStatic(m.getModifiers())) return false;
        ITypeBinding ret = m.getReturnType();
        if (ret == null || !"void".equals(ret.getQualifiedName())) return false;
        ITypeBinding[] params = m.getParameterTypes();
        if (params.length != 1) return false;
        ITypeBinding p = params[0];
        if (p == null || !p.isArray() || p.getDimensions() != 1) return false;
        ITypeBinding elem = p.getElementType();
        return elem != null && "java.lang.String".equals(elem.getQualifiedName());
    }
}
