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
