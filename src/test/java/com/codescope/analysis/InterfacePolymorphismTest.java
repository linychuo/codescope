package com.codescope.analysis;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.index.JdtIndexer;
import com.codescope.index.ProjectIndex;
import com.codescope.model.MethodKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regressions for issue #9 item 1 (接口多态未展开): a call whose receiver is a
 * <em>project interface</em> that inherits the method from a
 * <em>class</em> supertype (project abstract class, or a library class) is
 * not bound by JDT — {@code ITypeBinding.getInterfaces()} does not expose an
 * interface's class supertype — so the call edge used to vanish.
 *
 * <p>The indexer now keeps such invocations as
 * {@link ProjectIndex.UnresolvedCall}s and attributes them at query time by
 * receiver type + name + arity, and the type hierarchy records library
 * supertypes so the receiver is hierarchy-comparable with the target.
 */
class InterfacePolymorphismTest {

    private static ProjectIndex build(Path srcDir, Path tmp, String... files) throws Exception {
        List<Path> sources = new java.util.ArrayList<>();
        for (String f : files) sources.add(srcDir.resolve(f));
        return new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
    }

    /** Runs the same resolution the tools use, then the caller BFS. */
    private static CallChainAnalyzer.Result trace(ProjectIndex index, String cls, String name)
            throws Exception {
        MethodResolver.Result res = MethodResolver.resolve(index, cls, name, null, null);
        MethodKey display;
        List<MethodKey> seeds;
        if (res instanceof MethodResolver.Result.ProjectTarget pt) {
            display = pt.target();
            seeds = List.of(display);
        } else {
            MethodResolver.Result.LibrarySeeds ls = (MethodResolver.Result.LibrarySeeds) res;
            display = ls.display();
            seeds = ls.seeds();
        }
        return new CallChainAnalyzer().traceCallers(index, display, seeds);
    }

    private static Set<String> callerClasses(CallNode node) {
        Set<String> out = new java.util.HashSet<>();
        for (CallNode c : node.callers) {
            out.add(c.className);
            out.addAll(callerClasses(c));
        }
        return out;
    }

    @Test
    void interfaceExtendsAbstractClassCallIsAttributed(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Abs.java"), """
                package com.example;
                public abstract class Abs { public abstract void m(); }
                """);
        Files.writeString(srcDir.resolve("I.java"), """
                package com.example;
                public interface I extends Abs { }
                """);
        Files.writeString(srcDir.resolve("Impl.java"), """
                package com.example;
                public class Impl implements I { public void m() {} }
                """);
        Files.writeString(srcDir.resolve("Caller.java"), """
                package com.example;
                public class Caller { void go(I i) { i.m(); } }
                """);
        ProjectIndex index = build(srcDir, tmp, "Abs.java", "I.java", "Impl.java", "Caller.java");

        // The concrete implementation is what a user would trace: the call
        // through the interface-typed receiver must reach it.
        CallChainAnalyzer.Result impl = trace(index, "com.example.Impl", "m");
        assertTrue(impl.found());
        assertTrue(callerClasses(impl.root()).contains("com.example.Caller"),
                "call through I must surface on Impl#m, got: " + impl.root().toJson());

        // The abstract declaration is part of the same family.
        CallChainAnalyzer.Result abs = trace(index, "com.example.Abs", "m");
        assertTrue(callerClasses(abs.root()).contains("com.example.Caller"),
                "call through I must surface on Abs#m, got: " + abs.root().toJson());
    }

    @Test
    void interfaceExtendsLibraryClassCallIsAttributed(@TempDir Path tmp) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Base.java"), """
                package com.example;
                import java.util.ArrayList;
                public interface Base extends ArrayList<String> { }
                """);
        Files.writeString(srcDir.resolve("MyList.java"), """
                package com.example;
                public interface MyList extends Base { }
                """);
        Files.writeString(srcDir.resolve("Caller.java"), """
                package com.example;
                public class Caller { void go(MyList l) { l.size(); } }
                """);
        ProjectIndex index = build(srcDir, tmp, "Base.java", "MyList.java", "Caller.java");

        // `size` is not declared in the project; the query resolves through
        // the unresolved-call fallback to the library declaration.
        CallChainAnalyzer.Result r = trace(index, "com.example.MyList", "size");
        assertTrue(r.found());
        assertTrue(callerClasses(r.root()).contains("com.example.Caller"),
                "call through MyList must surface on the inherited size(), got: "
                        + r.root().toJson());
    }

    @Test
    void siblingImplementationDoesNotInheritTheCall(@TempDir Path tmp) throws Exception {
        // Guard the issue #7 / #8 invariant on top of the fallback: an
        // unresolved call on a MyList receiver must not surface on an
        // unrelated sibling interface that happens to extend the same
        // library class.
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("Base.java"), """
                package com.example;
                import java.util.ArrayList;
                public interface Base extends ArrayList<String> { }
                """);
        Files.writeString(srcDir.resolve("MyList.java"), """
                package com.example;
                public interface MyList extends Base { }
                """);
        Files.writeString(srcDir.resolve("OtherList.java"), """
                package com.example;
                public interface OtherList extends Base { }
                """);
        Files.writeString(srcDir.resolve("Caller.java"), """
                package com.example;
                public class Caller { void go(MyList l) { l.size(); } }
                """);
        ProjectIndex index = build(srcDir, tmp, "Base.java", "MyList.java",
                "OtherList.java", "Caller.java");

        CallChainAnalyzer.Result r = trace(index, "com.example.OtherList", "size");
        assertTrue(r.found());
        assertFalse(callerClasses(r.root()).contains("com.example.Caller"),
                "OtherList is a sibling of MyList; the call on a MyList receiver must not "
                        + "surface there, got: " + r.root().toJson());
    }
}
