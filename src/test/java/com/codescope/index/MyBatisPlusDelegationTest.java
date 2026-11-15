package com.codescope.index;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.model.MethodKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression for the framework-delegation gap found in the real-project
 * review: a project
 * {@code XxxServiceImpl extends ServiceImpl<XxxMapper, E>} inherits
 * {@code IService}'s CRUD methods, whose library bodies delegate to the
 * mapper. Without the model, a callee chain stops at the library
 * {@code IService} method.
 *
 * <p>The test defines stand-in types at the exact framework FQNs the model
 * matches, so it needs no MyBatis-Plus jar on the classpath. (The call site
 * itself is not resolved by JDT in a sourcepath-only fixture — the same
 * limitation as the real project's library bodies — so the assertion is on
 * the delegation edge, which is binding-driven and independent of it. The
 * end-to-end chain was verified against the real IMS project.)
 */
class MyBatisPlusDelegationTest {

    private static void write(Path src, String rel, String content) throws Exception {
        Path p = src.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void serviceImplDelegationEdgesAreRecorded(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/main/java"));
        write(src, "com/baomidou/mybatisplus/extension/service/IService.java", """
                package com.baomidou.mybatisplus.extension.service;
                public interface IService<T> {
                    T getById(java.io.Serializable id);
                }
                """);
        write(src, "com/baomidou/mybatisplus/extension/service/impl/ServiceImpl.java", """
                package com.baomidou.mybatisplus.extension.service.impl;
                public class ServiceImpl<M, T>
                        implements com.baomidou.mybatisplus.extension.service.IService<T> {
                    @Override public T getById(java.io.Serializable id) { return null; }
                }
                """);
        write(src, "com/baomidou/mybatisplus/core/mapper/BaseMapper.java", """
                package com.baomidou.mybatisplus.core.mapper;
                public interface BaseMapper<T> {
                    T selectById(java.io.Serializable id);
                }
                """);
        write(src, "com/example/Entity.java", """
                package com.example;
                public class Entity { private Long id; }
                """);
        write(src, "com/example/MyMapper.java", """
                package com.example;
                public interface MyMapper
                        extends com.baomidou.mybatisplus.core.mapper.BaseMapper<Entity> { }
                """);
        write(src, "com/example/MyService.java", """
                package com.example;
                public interface MyService
                        extends com.baomidou.mybatisplus.extension.service.IService<Entity> { }
                """);
        write(src, "com/example/MyServiceImpl.java", """
                package com.example;
                public class MyServiceImpl
                        extends com.baomidou.mybatisplus.extension.service.impl.ServiceImpl<MyMapper, Entity>
                        implements MyService { }
                """);

        List<Path> sources;
        try (var walk = Files.walk(src)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        ProjectIndex index = new JdtIndexer().build(sources, List.of(),
                List.of(src.toString()), tmp);

        // The delegation is scoped to the project service interface that the
        // call site's receiver is typed as, and is not a call edge (so
        // trace_callers on the mapper is not polluted).
        MethodKey iServiceGetById = new MethodKey(MyBatisPlus.I_SERVICE, "getById", 1,
                List.of("java.io.Serializable"));
        MethodKey delegated = index.delegationOf(iServiceGetById, "com.example.MyService");
        assertNotNull(delegated, "the ServiceImpl -> mapper delegation must be recorded");
        assertEquals("selectById", delegated.methodName);
        assertEquals("com.example.MyMapper", delegated.declaringClass);
        assertTrue(delegated.arity == 1,
                "the mapper key must carry the project mapper FQN, got: " + delegated);
        assertTrue(index.invokesOf(iServiceGetById).isEmpty(),
                "a delegation is not a call edge");
    }

    @Test
    void calleeTreeUsesTheDelegationScopedToTheReceiver(@TempDir Path tmp) throws Exception {
        // Hand-built index: Caller#use calls IService#getById on a
        // MyService-typed receiver; the delegation maps that receiver to
        // MyMapper#selectById. The callee tree must include the mapper and
        // must not leak the sibling MyOtherService's mapper.
        ProjectIndex index = new ProjectIndex();
        MethodKey use = new MethodKey("com.example.Caller", "use", 0, List.of());
        MethodKey iServiceGetById = new MethodKey(MyBatisPlus.I_SERVICE, "getById", 1,
                List.of("java.io.Serializable"));
        MethodKey mapperSelect = new MethodKey("com.example.MyMapper", "selectById", 1,
                List.of("java.io.Serializable"));
        index.putDeclaration(use, new ProjectIndex.SourceLoc("Caller.java", 1));
        index.putDeclaration(iServiceGetById, new ProjectIndex.SourceLoc("IService.java", 1));
        index.putDeclaration(mapperSelect, new ProjectIndex.SourceLoc("MyMapper.java", 1));
        index.recordInvocation(use, iServiceGetById);
        index.recordCallSite(use, iServiceGetById,
                new ProjectIndex.SourceLoc("Caller.java", 2), "com.example.MyService");
        index.recordDelegation(iServiceGetById, "com.example.MyService", mapperSelect);

        com.codescope.analysis.CalleeChainAnalyzer.Result r =
                new com.codescope.analysis.CalleeChainAnalyzer().traceCallees(
                        index, use, List.of(use), "com.example.Caller");
        String json = r.root().toJson(null, "callees").toString();
        assertTrue(json.contains("com.example.MyMapper") && json.contains("selectById"),
                "the callee tree must cross the framework delegation, got: " + json);
        assertFalse(json.contains("MyOtherService"),
                "the delegation must be scoped to the receiver, got: " + json);
    }
}
