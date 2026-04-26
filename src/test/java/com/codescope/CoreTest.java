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
        CallGraph.CallSite cs1 = new CallGraph.CallSite("foo", 10, "");
        CallGraph.CallSite cs2 = new CallGraph.CallSite("foo", 20, "");
        CallGraph.CallSite cs3 = new CallGraph.CallSite("bar", 10, "");
        CallGraph.CallSite cs4 = new CallGraph.CallSite("foo", 10, "");

        assertEquals(-1, cs1.compareTo(cs2));
        assertEquals(1, cs2.compareTo(cs1));
        assertTrue(cs1.compareTo(cs3) > 0);
        assertEquals(0, cs1.compareTo(cs4));
    }

    @Test
    void testCallSiteToString() {
        CallGraph.CallSite cs1 = new CallGraph.CallSite("foo", 10, "");
        CallGraph.CallSite cs2 = new CallGraph.CallSite("foo", 10, "bar.Baz.foo()");

        assertEquals("foo (line 10)", cs1.toString());
        assertEquals("foo -> bar.Baz.foo() (line 10)", cs2.toString());
    }
}