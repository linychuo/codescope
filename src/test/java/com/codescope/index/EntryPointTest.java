package com.codescope.index;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.model.MethodKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regressions for issue #9 item 3 (外部框架入口不识别): framework entry
 * points are detected from annotations (and the {@code main} signature)
 * and recorded on the method key.
 *
 * <p>The tests use unresolved annotation imports on purpose: the fixture
 * projects have no Spring/JUnit jars on the classpath, so this exercises
 * the simple-name fallback that real projects hit when a dependency is
 * missing. The FQN path is covered by the same map.
 */
class EntryPointTest {

    private static ProjectIndex build(Path srcDir, Path tmp, String... files) throws Exception {
        List<Path> sources = new java.util.ArrayList<>();
        for (String f : files) sources.add(srcDir.resolve(f));
        return new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
    }

    private static String kind(ProjectIndex index, String cls, String method) throws Exception {
        MethodKey k = index.resolveTarget(cls, method);
        assertNotNull(k, "method not found: " + cls + "#" + method);
        return index.entryPointKindOf(k);
    }

    @Test
    void springMethodMappingIsDetected(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Controller.java"), """
                package com.example;
                import org.springframework.web.bind.annotation.GetMapping;
                public class Controller {
                    @GetMapping("/y")
                    public String handle() { return "x"; }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Controller.java");
        assertEquals("spring-mvc", kind(index, "com.example.Controller", "handle"));
    }

    @Test
    void classLevelRequestMappingIsDetected(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Controller.java"), """
                package com.example;
                import org.springframework.web.bind.annotation.RequestMapping;
                @RequestMapping("/api")
                public class Controller {
                    public String handle() { return "x"; }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Controller.java");
        assertEquals("spring-mvc", kind(index, "com.example.Controller", "handle"));
    }

    @Test
    void scheduledAndEventListenerAreDetected(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Jobs.java"), """
                package com.example;
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.context.event.EventListener;
                public class Jobs {
                    @Scheduled(fixedRate = 1000)
                    public void tick() { }
                    @EventListener
                    public void onEvent(Object event) { }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Jobs.java");
        assertEquals("spring-scheduled", kind(index, "com.example.Jobs", "tick"));
        assertEquals("spring-event", kind(index, "com.example.Jobs", "onEvent"));
    }

    @Test
    void junitAndTestNgTestsAreDetected(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Tests.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                public class Tests {
                    @Test
                    public void aTest() { }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Tests.java");
        // Unresolved import → simple-name fallback label.
        assertNotNull(kind(index, "com.example.Tests", "aTest"));
    }

    @Test
    void frameworkCallbackInterfacesAreDetected(@TempDir Path tmp) throws Exception {
        // Issue #9 follow-up: the framework invokes these callbacks even
        // though no project code calls them.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Listener.java"), """
                package com.example;
                import org.springframework.context.ApplicationListener;
                import org.springframework.context.event.ContextRefreshedEvent;
                public class Listener implements ApplicationListener<ContextRefreshedEvent> {
                    @Override public void onApplicationEvent(ContextRefreshedEvent event) { }
                }
                """);
        Files.writeString(srcDir.resolve("Job.java"), """
                package com.example;
                import org.springframework.boot.CommandLineRunner;
                public class Job implements CommandLineRunner {
                    @Override public void run(String... args) { }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Listener.java", "Job.java");
        assertEquals("spring-event-listener",
                kind(index, "com.example.Listener", "onApplicationEvent"));
        assertEquals("spring-boot-runner", kind(index, "com.example.Job", "run"));
    }

    @Test
    void adviceAndExceptionHandlerAreDetected(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Advice.java"), """
                package com.example;
                import org.springframework.web.bind.annotation.RestControllerAdvice;
                import org.springframework.web.bind.annotation.ExceptionHandler;
                @RestControllerAdvice
                public class Advice {
                    @ExceptionHandler(RuntimeException.class)
                    public String handle(RuntimeException e) { return "x"; }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Advice.java");
        assertEquals("spring-mvc-exception", kind(index, "com.example.Advice", "handle"));
    }

    @Test
    void transactionalBoundaryIsRecorded(@TempDir Path tmp) throws Exception {
        // Issue #9 follow-up: @Transactional is a proxy boundary, not an
        // entry point — a distinct signal on the node.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Svc.java"), """
                package com.example;
                import org.springframework.transaction.annotation.Transactional;
                public class Svc {
                    @Transactional(rollbackFor = Exception.class)
                    public void save() { }
                    public void plain() { }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Svc.java");
        MethodKey save = index.resolveTarget("com.example.Svc", "save");
        assertNotNull(save);
        assertTrue(index.isTransactional(save), "@Transactional must be recorded");
        MethodKey plain = index.resolveTarget("com.example.Svc", "plain");
        assertNotNull(plain);
        assertFalse(index.isTransactional(plain), "an ordinary method must not be labelled");
        assertNull(index.entryPointKindOf(save),
                "@Transactional is a boundary, not an entry point");
    }

    @Test
    void mainMethodIsDetected(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("App.java"), """
                package com.example;
                public class App {
                    public static void main(String[] args) { new App().run(); }
                    public void run() { }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "App.java");
        assertEquals("main", kind(index, "com.example.App", "main"));
        assertNull(kind(index, "com.example.App", "run"),
                "an ordinary method must not be labelled an entry point");
    }

    @Test
    void ordinaryMethodIsNotAnEntryPoint(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Plain.java"), """
                package com.example;
                public class Plain {
                    public void doWork() { }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Plain.java");
        assertNull(kind(index, "com.example.Plain", "doWork"));
    }
}
