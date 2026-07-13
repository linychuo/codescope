package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the new fan-out marker fields on {@link CallNode}: a marker
 * carries `truncatedCallers: N` in its JSON and the boolean flag round-trips
 * to JSON for a regular node that exceeded the fan-out cap.
 */
class CallNodeTruncationTest {

    @Test
    void fanoutMarkerJsonIncludesTruncatedCallers() {
        CallNode marker = CallNode.fanoutMarker("com.example.Wide", "hot", 0, 1234);
        Map<String, Object> json = marker.toJson();
        assertEquals("com.example.Wide", json.get("class"));
        assertEquals("hot", json.get("method"));
        assertEquals(0, json.get("arity"));
        assertEquals(1234, json.get("truncatedCallers"),
                "fan-out marker must report how many callers were dropped");
        // Sanity: it does not also emit a depth-truncation flag.
        assertFalse(json.containsKey("truncated"));
        assertFalse(json.containsKey("cycle"));
    }

    @Test
    void regularNodeOmitsTruncatedCallers() {
        // A regular node (no marker) should not emit `truncatedCallers` at all.
        CallNode node = new CallNode("a.B", "m", 0, "B.java", 10);
        Map<String, Object> json = node.toJson();
        assertFalse(json.containsKey("truncatedCallers"));
    }
}
