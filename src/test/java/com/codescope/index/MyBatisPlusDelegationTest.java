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

        // The delegation edge must be recorded against the key call sites
        // actually bind to (the library IService method).
        MethodKey iServiceGetById = new MethodKey(MyBatisPlus.I_SERVICE, "getById", 1,
                List.of("java.io.Serializable"));
        Set<MethodKey> callees = index.invokesOf(iServiceGetById);
        assertTrue(callees.stream().anyMatch(k ->
                        k.declaringClass.equals("com.example.MyMapper")
                                && k.methodName.equals("selectById")),
                "the ServiceImpl -> mapper delegation must be recorded, got: " + callees);

        // The mapper method key uses the project mapper's FQN, so the
        // mapper's XML statement attaches to the callee tree.
        assertTrue(callees.stream().anyMatch(k ->
                        k.declaringClass.equals("com.example.MyMapper")
                                && k.arity == 1),
                "the mapper key must carry the project mapper FQN, got: " + callees);
    }
}
