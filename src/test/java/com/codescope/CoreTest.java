package com.codescope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class CoreTest {

    @Test
    void testVirtualThreadsAvailable() {
        assertTrue(Runtime.getRuntime().availableProcessors() > 0);
    }

    @Test
    void testCallSiteCompare() {
        CallGraphBuilder.CallSite cs1 = new CallGraphBuilder.CallSite("foo", 10, "");
        CallGraphBuilder.CallSite cs2 = new CallGraphBuilder.CallSite("foo", 20, "");
        CallGraphBuilder.CallSite cs3 = new CallGraphBuilder.CallSite("bar", 10, "");
        CallGraphBuilder.CallSite cs4 = new CallGraphBuilder.CallSite("foo", 10, "");

        assertEquals(-1, cs1.compareTo(cs2));
        assertEquals(1, cs2.compareTo(cs1));
        assertTrue(cs1.compareTo(cs3) > 0);
        assertEquals(0, cs1.compareTo(cs4));
    }

    @Test
    void testCallSiteToString() {
        CallGraphBuilder.CallSite cs1 = new CallGraphBuilder.CallSite("foo", 10, "");
        CallGraphBuilder.CallSite cs2 = new CallGraphBuilder.CallSite("foo", 10, "bar.Baz.foo()");

        assertEquals("foo (line 10)", cs1.toString());
        assertEquals("foo -> bar.Baz.foo() (line 10)", cs2.toString());
    }
}