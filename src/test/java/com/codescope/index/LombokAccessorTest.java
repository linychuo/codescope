package com.codescope.index;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.analysis.CallChainAnalyzer;
import com.codescope.model.MethodKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regressions for the Lombok gap found in the real-project review: JDT does
 * not run the Lombok annotation processor, so {@code @Data}/{@Getter}/
 * {@code @Setter} accessors are invisible and their call sites are
 * unattributable. The indexer synthesizes the declarations.
 */
class LombokAccessorTest {

    private static ProjectIndex build(Path srcDir, Path tmp, String... files) throws Exception {
        List<Path> sources = new java.util.ArrayList<>();
        for (String f : files) sources.add(srcDir.resolve(f));
        return new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
    }

    private static Set<String> callerClasses(CallChainAnalyzer.Result r) {
        Set<String> out = new java.util.HashSet<>();
        for (var c : r.root().callers) out.add(c.className);
        return out;
    }

    @Test
    void dataClassAccessorsAreSynthesizedAndCallable(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("User.java"), """
                package com.example;
                import lombok.Data;
                @Data
                public class User {
                    private String name;
                    private boolean active;
                    private Long id;
                }
                """);
        Files.writeString(srcDir.resolve("Caller.java"), """
                package com.example;
                public class Caller {
                    String use(User u) { return u.getName() + u.isActive(); }
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "User.java", "Caller.java");

        MethodKey getName = index.resolveTarget("com.example.User", "getName");
        assertNotNull(getName, "Lombok getter must be resolvable");
        assertEquals(0, getName.arity);
        MethodKey setName = index.resolveTarget("com.example.User", "setName");
        assertNotNull(setName, "Lombok setter must be resolvable");
        assertEquals(1, setName.arity);
        assertEquals(List.of("java.lang.String"), setName.parameterTypes);
        assertNotNull(index.resolveTarget("com.example.User", "isActive"),
                "a primitive boolean getter uses the isX form");
        assertNotNull(index.resolveTarget("com.example.User", "setActive"));

        // The call sites JDT cannot resolve are attributed by the fallback.
        CallChainAnalyzer.Result r = new CallChainAnalyzer().traceCallers(
                index, getName, "com.example.User");
        assertTrue(callerClasses(r).contains("com.example.Caller"),
                "the synthesized getter must be callable, got: " + r.root().toJson());
    }

    @Test
    void fieldLevelGetterFinalAndStaticAreHandled(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Mixed.java"), """
                package com.example;
                import lombok.Getter;
                public class Mixed {
                    @Getter private String code;
                    private final String immutable = "x";
                    private static String CONST = "y";
                }
                """);
        ProjectIndex index = build(srcDir, tmp, "Mixed.java");

        assertNotNull(index.resolveTarget("com.example.Mixed", "getCode"),
                "field-level @Getter must be synthesized");
        assertNull(index.resolveTarget("com.example.Mixed", "setCode"),
                "no class-level @Setter → no setter");
        assertNull(index.resolveTarget("com.example.Mixed", "getImmutable"),
                "a final field gets no getter without @Getter");
        assertNull(index.resolveTarget("com.example.Mixed", "getCONST"),
                "Lombok skips static fields");
    }
}
