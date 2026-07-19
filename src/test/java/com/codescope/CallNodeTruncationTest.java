package com.codescope;

import org.junit.jupiter.api.Test;

import java.util.List;
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

    @Test
    void regularNodeOmitsNodeCapTruncated() {
        // A fresh tree — never touched by a node-cap event — must not
        // emit `nodeCapTruncated` in its JSON. The flag is reserved for
        // trees the analyzer bailed out of at the MAX_NODES safety cap.
        CallNode root = new CallNode("a.B", "m", 0, "B.java", 10);
        Map<String, Object> json = root.toJson();
        assertFalse(json.containsKey("nodeCapTruncated"),
                "regular CallNode must not emit nodeCapTruncated until the analyzer sets it");
    }

    @Test
    void nodeCapTruncatedRoundTripsToJson() {
        CallNode root = new CallNode("com.example.Boom", "hot", 0, "Boom.java", 42);
        Map<String, Object> before = root.toJson();
        assertFalse(before.containsKey("nodeCapTruncated"));

        root.markNodeCapTruncated();
        Map<String, Object> after = root.toJson();
        assertEquals(Boolean.TRUE, after.get("nodeCapTruncated"),
                "analyzer must be able to mark the root after construction so a partial "
                        + "tree at MAX_NODES carries a structured signal, not just the message");
    }

    @Test
    void nodeCapTruncatedDoesNotPropagateToChildren() {
        // nodeCapTruncated is whole-tree metadata; only the root should
        // carry it. Marking the root must NOT also mark its children.
        CallNode root = new CallNode("com.example.Top", "hot", 0, "Top.java", 1);
        CallNode child = new CallNode("com.example.Mid", "callee", 0, "Mid.java", 2);
        root.addChild(child);
        root.markNodeCapTruncated();

        Map<String, Object> rootJson = root.toJson();
        assertEquals(Boolean.TRUE, rootJson.get("nodeCapTruncated"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kids =
                (List<Map<String, Object>>) rootJson.get("callers");
        Map<String, Object> childJson = kids.get(0);
        assertFalse(childJson.containsKey("nodeCapTruncated"),
                "child nodes must not inherit the root's node-cap marker");
    }

    @Test
    void nodeCapTruncatedIsDistinctFromDepthMarkerTruncated() {
        // The existing `truncated` flag means "this branch hit MAX_DEPTH"
        // (per-node). nodeCapTruncated means "the whole tree was cut at
        // MAX_NODES". A depth marker must NOT also carry nodeCapTruncated.
        CallNode depthMarker = CallNode.depthMarker("com.example.Deep", "leaf", 0);
        Map<String, Object> json = depthMarker.toJson();
        assertEquals(Boolean.TRUE, json.get("truncated"));
        assertFalse(json.containsKey("nodeCapTruncated"),
                "depth marker is per-branch, not whole-tree — must not carry nodeCapTruncated");
    }
}
