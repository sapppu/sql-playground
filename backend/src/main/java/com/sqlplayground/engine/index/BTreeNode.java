package com.sqlplayground.engine.index;

import java.util.*;

/**
 * B-Tree node for the in-memory index engine.
 * Min degree t=3 → max 5 keys per node, min 2 keys (except root).
 * Leaf nodes store row references per key via values list.
 * Leaf chaining via 'next' pointer enables efficient range scans.
 */
public class BTreeNode {

    public static final int T = 3; // minimum degree

    public boolean isLeaf;
    public List<Comparable> keys;
    public List<List<Map<String, Object>>> values; // leaf only: rows per key
    public List<BTreeNode> children;                // internal only
    public BTreeNode next;                          // leaf chaining

    public BTreeNode(boolean isLeaf) {
        this.isLeaf = isLeaf;
        this.keys = new ArrayList<>();
        this.values = isLeaf ? new ArrayList<>() : null;
        this.children = isLeaf ? null : new ArrayList<>();
        this.next = null;
    }

    public boolean isFull() {
        return keys.size() >= 2 * T - 1; // max 5 keys
    }
}
