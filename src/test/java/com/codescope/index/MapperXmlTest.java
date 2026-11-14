package com.codescope.index;

import static org.junit.jupiter.api.Assertions.*;
import com.codescope.analysis.CalleeChainAnalyzer;
import com.codescope.analysis.CallChainAnalyzer;
import com.codescope.analysis.MethodResolver;
import com.codescope.model.MethodKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regressions for issue #9 item 4 (不索引 mybatis xml): mapper XML files
 * are parsed and their statements indexed, then attached to the mapper
 * interface method's callee tree.
 */
class MapperXmlTest {

    private static ProjectIndex build(Path tmp, String... javaFiles) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Path resDir = Files.createDirectories(tmp.resolve("src/main/resources/com/example"));
        Files.createDirectories(tmp.resolve("src/main/java"));
        java.util.List<Path> sources = new java.util.ArrayList<>();
        for (String f : javaFiles) sources.add(srcDir.resolve(f));
        return new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp);
    }

    private static Path writeMapperXml(Path tmp, String content) throws Exception {
        Path resDir = Files.createDirectories(tmp.resolve("src/main/resources/com/example"));
        Path xml = resDir.resolve("UserMapper.xml");
        Files.writeString(xml, content);
        return xml;
    }

    private static void writeJava(Path tmp, String content) throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(srcDir.resolve("UserMapper.java"), content);
    }

    private static String mapperXml(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.UserMapper">
                %s
                </mapper>
                """.formatted(body);
    }

    @Test
    void mapperStatementsAreIndexedAndAttachedToCallees(@TempDir Path tmp) throws Exception {
        writeJava(tmp, """
                package com.example;
                public interface UserMapper { String findById(Long id); }
                """);
        writeMapperXml(tmp, mapperXml("""
                <select id="findById" resultType="String">
                  SELECT * FROM user WHERE id = #{id}
                </select>
                """));

        ProjectIndex index = build(tmp, "UserMapper.java");

        // The statement is indexed against the mapper interface method.
        List<ProjectIndex.MapperStatement> statements =
                index.mapperStatementsFor("com.example.UserMapper", "findById");
        assertEquals(1, statements.size(), "expected one statement, got: " + index.mapperStatements());
        assertEquals("select", statements.get(0).type());
        assertTrue(statements.get(0).sql().contains("SELECT * FROM user"),
                "SQL body should be captured, got: " + statements.get(0).sql());

        // And it is searchable as a symbol.
        ProjectIndex.SymbolMatchResult symbols =
                index.searchSymbols("findById", "mapper-statement", 100);
        assertEquals(1, symbols.totalCount(), "statement should be a symbol, got: " + symbols);

        // And it appears in the callee tree of the mapper method.
        MethodResolver.Result res = MethodResolver.resolve(
                index, "com.example.UserMapper", "findById", null, null);
        MethodKey display = (res instanceof MethodResolver.Result.ProjectTarget pt)
                ? pt.target() : ((MethodResolver.Result.LibrarySeeds) res).display();
        CalleeChainAnalyzer.Result r = new CalleeChainAnalyzer().traceCallees(
                index, display, List.of(display));
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> callees =
                (List<java.util.Map<String, Object>>) r.root().toJson(null, "callees").get("callees");
        assertEquals(1, callees.size(), "the mapper method has one SQL leaf: " + callees);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> sql =
                (java.util.Map<String, Object>) callees.get(0).get("sql");
        assertNotNull(sql, "the node must carry an `sql` object, got: " + callees);
        assertEquals("select", sql.get("type"));
        assertTrue(sql.get("text").toString().contains("SELECT * FROM user"),
                "SQL body must be attached, got: " + sql);
    }

    @Test
    void dynamicSqlTagsArePreserved(@TempDir Path tmp) throws Exception {
        writeJava(tmp, """
                package com.example;
                public interface UserMapper { String search(String name); }
                """);
        writeMapperXml(tmp, mapperXml("""
                <select id="search" resultType="String">
                  SELECT * FROM user
                  <where>
                    <if test="name != null">AND name = #{name}</if>
                  </where>
                </select>
                """));

        ProjectIndex index = build(tmp, "UserMapper.java");
        List<ProjectIndex.MapperStatement> statements =
                index.mapperStatementsFor("com.example.UserMapper", "search");
        assertEquals(1, statements.size());
        String sql = statements.get(0).sql();
        assertTrue(sql.contains("<if"), "dynamic SQL tags should be preserved, got: " + sql);
        assertTrue(sql.contains("</where>"), "closing tags should be preserved, got: " + sql);
    }

    @Test
    void sqlFragmentsAreNotAttachedAsMethodImplementations(@TempDir Path tmp) throws Exception {
        // A <sql id="..."> element is a reusable fragment, not the
        // implementation of a mapper method; only select/insert/update/
        // delete may attach to the callee tree.
        writeJava(tmp, """
                package com.example;
                public interface UserMapper { String findById(Long id); }
                """);
        writeMapperXml(tmp, mapperXml("""
                <sql id="findById">SELECT 1</sql>
                <select id="findById" resultType="String">SELECT 2</select>
                """));
        ProjectIndex index = build(tmp, "UserMapper.java");

        List<ProjectIndex.MapperStatement> impl =
                index.mapperStatementsFor("com.example.UserMapper", "findById");
        assertEquals(1, impl.size(),
                "only the <select> implements the method, got: " + index.mapperStatements());
        assertEquals("select", impl.get(0).type());
        assertTrue(impl.get(0).sql().contains("SELECT 2"));

        // The fragment is still indexed (searchable), just not attached.
        assertTrue(index.mapperStatements().stream()
                        .anyMatch(s -> s.type().equals("sql") && s.sql().contains("SELECT 1")),
                "the fragment should remain searchable, got: " + index.mapperStatements());

        MethodResolver.Result res = MethodResolver.resolve(
                index, "com.example.UserMapper", "findById", 1, List.of("java.lang.Long"));
        MethodKey display = (res instanceof MethodResolver.Result.ProjectTarget pt)
                ? pt.target() : ((MethodResolver.Result.LibrarySeeds) res).display();
        CalleeChainAnalyzer.Result r = new CalleeChainAnalyzer().traceCallees(
                index, display, List.of(display), "com.example.UserMapper");
        String json = r.root().toJson(null, "callees").toString();
        assertTrue(json.contains("SELECT 2"), "the real statement must be attached, got: " + json);
        assertFalse(json.contains("SELECT 1"),
                "the <sql> fragment must not be attached, got: " + json);
    }

    @Test
    void includeRefidIsExpandedFromSqlFragment(@TempDir Path tmp) throws Exception {
        // Issue #9 follow-up: the SQL shown to the user must be the resolved
        // statement, with <include refid> replaced by the <sql> fragment.
        writeJava(tmp, """
                package com.example;
                public interface UserMapper { String findById(Long id); }
                """);
        writeMapperXml(tmp, mapperXml("""
                <sql id="cols">id, name</sql>
                <select id="findById" resultType="String">
                  SELECT <include refid="cols"/> FROM user
                </select>
                """));
        ProjectIndex index = build(tmp, "UserMapper.java");

        List<ProjectIndex.MapperStatement> stmts =
                index.mapperStatementsFor("com.example.UserMapper", "findById");
        assertEquals(1, stmts.size());
        assertTrue(stmts.get(0).sql().contains("id, name"),
                "include must be expanded, got: " + stmts.get(0).sql());
        assertFalse(stmts.get(0).sql().contains("<include"),
                "no include tag should remain, got: " + stmts.get(0).sql());
    }

    @Test
    void resultMapIsIndexedButNotAttachedToCallees(@TempDir Path tmp) throws Exception {
        // Issue #9 follow-up: <resultMap> is part of the mapper XML and
        // searchable, but it is not a method implementation.
        writeJava(tmp, """
                package com.example;
                public interface UserMapper { String findById(Long id); }
                """);
        writeMapperXml(tmp, mapperXml("""
                <resultMap id="BaseResultMap" type="com.example.User">
                  <id column="id" property="id"/>
                </resultMap>
                """));
        ProjectIndex index = build(tmp, "UserMapper.java");

        ProjectIndex.SymbolMatchResult res =
                index.searchSymbols("BaseResultMap", "mapper-statement", 100);
        assertEquals(1, res.totalCount(), "resultMap should be searchable, got: " + res);
        assertTrue(index.mapperStatementsFor("com.example.UserMapper", "BaseResultMap").isEmpty(),
                "a resultMap must not attach to a callee tree");
    }

    @Test
    void testResourceMappersAreSkippedUnlessIncluded(@TempDir Path tmp) throws Exception {
        // include_tests governs the Java source walk; the mapper XML scan
        // must obey it too (src/<...>/test/resources is a test tree).
        Path srcDir = Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Path testRes = Files.createDirectories(tmp.resolve("src/test/resources/com/example"));
        Files.writeString(srcDir.resolve("UserMapper.java"), """
                package com.example;
                public interface UserMapper { String findById(Long id); }
                """);
        Files.writeString(testRes.resolve("UserMapper.xml"), """
                <?xml version="1.0"?>
                <mapper namespace="com.example.UserMapper">
                  <select id="findById" resultType="String">SELECT 1</select>
                </mapper>
                """);
        java.util.List<Path> sources = List.of(srcDir.resolve("UserMapper.java"));

        ProjectIndex withoutTests = new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp, false);
        assertTrue(withoutTests.mapperStatements().isEmpty(),
                "test-resource mappers must be skipped when include_tests=false, got: "
                        + withoutTests.mapperStatements());

        ProjectIndex withTests = new JdtIndexer().build(sources, List.of(),
                List.of(srcDir.getParent().getParent().toString()), tmp, true);
        assertEquals(1, withTests.mapperStatements().size(),
                "include_tests=true must index the test-resource mapper, got: "
                        + withTests.mapperStatements());
    }

    @Test
    void nonMapperXmlIsIgnored(@TempDir Path tmp) throws Exception {
        writeJava(tmp, """
                package com.example;
                public interface UserMapper { String findById(Long id); }
                """);
        Path resDir = Files.createDirectories(tmp.resolve("src/main/resources"));
        Files.writeString(resDir.resolve("plain.xml"), """
                <?xml version="1.0"?>
                <configuration><property name="x" value="y"/></configuration>
                """);
        // A mapper XML without a namespace cannot be mapped to an interface.
        writeMapperXml(tmp, """
                <?xml version="1.0"?>
                <mapper>
                  <select id="orphan">SELECT 1</select>
                </mapper>
                """);

        ProjectIndex index = build(tmp, "UserMapper.java");
        assertTrue(index.mapperStatements().isEmpty(),
                "non-mapper / namespace-less XML must be ignored, got: " + index.mapperStatements());
    }

    @Test
    void doctypeDoesNotResolveExternalEntities(@TempDir Path tmp) throws Exception {
        // The DTD URL must not be fetched: the indexer disables DTD and
        // external entities. A statement is still parsed from the body.
        writeJava(tmp, """
                package com.example;
                public interface UserMapper { String findById(Long id); }
                """);
        writeMapperXml(tmp, mapperXml("""
                <select id="findById" resultType="String">SELECT 1</select>
                """));

        ProjectIndex index = build(tmp, "UserMapper.java");
        assertFalse(index.mapperStatements().isEmpty(),
                "mapper with a DOCTYPE should still be indexed");
        assertTrue(index.skippedFiles().isEmpty(),
                "no skipped-file noise expected, got: " + index.skippedFiles());
    }
}
