package com.codescope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the sub-interface scenario in issue #3: a
 * caller holds a field whose static type is a sub-interface (e.g.
 * {@code IfaceDaoSub extends IfaceDao}) that does NOT redeclare the
 * target method — it inherits the method from a supertype (e.g.
 * {@code IfaceDao}). JDT resolves the method binding to the supertype
 * (because that's where the method is declared), so the call edge is
 * recorded under {@code IfaceDao#findById/1}, not under
 * {@code IfaceDaoSub#findById/1}. {@code IfaceDaoSub#findById/1} does
 * not even exist as a declaration.
 *
 * <p>Before the fix, {@code trace_callers(IfaceDaoSub, findById)}
 * missed every caller because {@code resolveTarget} strictly looked
 * up {@code declarations.get(declaringClass=IfaceDaoSub, …)} — which
 * is empty. The fix walks {@code typeHierarchy} upward from the
 * user's className and tries to resolve the method on each ancestor.
 */
class ProjectIndexSubInterfaceTest {

    private ProjectIndex index;

    @BeforeEach
    void setUp() throws IOException {
        Path fixture = Path.of("src/test/resources/fixture-project");
        ProjectLoader.LoadResult load = new ProjectLoader().load(fixture, false);
        index = new JdtIndexer().build(load.sources(), load.classpath(), load.sourcepath(), fixture);
    }

    @Test
    void subtypesOfIncludesDirectSubInterface() {
        // Sanity check: the fixture declares IfaceDaoSub extends IfaceDao,
        // so IfaceDao's subtype set should contain IfaceDaoSub.
        assertTrue(index.subtypesOf("com.example.IfaceDao").contains("com.example.IfaceDaoSub"),
                "typeHierarchy must record IfaceDaoSub as a direct subtype of IfaceDao");
    }

    @Test
    void resolveTargetWalksUpToAncestorDeclaration() throws Exception {
        // The user queries IfaceDaoSub#findById — IfaceDaoSub does not
        // declare it; the declaration lives on the parent IfaceDao.
        MethodKey m = index.resolveTarget(
                "com.example.IfaceDaoSub", "findById", 1, List.of("long"));
        assertNotNull(m, "must walk up to IfaceDao and resolve findById there");
        assertEquals("com.example.IfaceDao", m.declaringClass,
                "resolved key should be the ancestor that actually declares the method");
        assertEquals("findById", m.methodName);
        assertEquals(1, m.arity);
    }

    @Test
    void findInvokedKeysWalksUpToAncestorCallEdge() {
        // Call edges are recorded under IfaceDao#findById/1 (the
        // method's actual declaring class). A query against the
        // sub-interface must find them via the ancestor walk.
        List<MethodKey> keys = index.findInvokedKeys(
                "com.example.IfaceDaoSub", "findById", 1, List.of("long"));
        assertFalse(keys.isEmpty(),
                "ancestor walk must surface call edges recorded under IfaceDao#findById/1");
        assertTrue(keys.stream().anyMatch(k -> "com.example.IfaceDao".equals(k.declaringClass)),
                "at least one returned key should be IfaceDao#findById/1");
    }
}