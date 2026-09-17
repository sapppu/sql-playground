package com.sqlplayground.engine.index;

import com.sqlplayground.engine.util.Values;

import java.util.*;

/**
 * B-Tree index for a single column. Supports:
 * - insert(key, row): add a row reference under a comparable key
 * - search(key): exact match — returns all rows with that key
 * - rangeSearch(low, high): returns all rows with keys in [low, high]
 */
public class BTreeIndex {

    private BTreeNode root;
    private final String columnName;

    public BTreeIndex(String columnName) {
        this.columnName = columnName;
        this.root = new BTreeNode(true);
    }

    public String getColumnName() {
        return columnName;
    }

    // ---- INSERT ----

    public void insert(Comparable key, Map<String, Object> row) {
        if (root.isFull()) {
            BTreeNode newRoot = new BTreeNode(false);
            newRoot.children.add(root);
            splitChild(newRoot, 0, root);
            root = newRoot;
        }
        insertNonFull(root, key, row);
    }

    @SuppressWarnings("unchecked")
    private void insertNonFull(BTreeNode node, Comparable key, Map<String, Object> row) {
        int i = node.keys.size() - 1;

        if (node.isLeaf) {
            // Find position for key (numeric-safe: mixed Long/Double keys coexist)
            while (i >= 0 && Values.compare(key, node.keys.get(i)) < 0) i--;

            if (i >= 0 && Values.compare(key, node.keys.get(i)) == 0) {
                // Duplicate key — append row to existing list
                node.values.get(i).add(row);
            } else {
                // New key — insert at i+1
                node.keys.add(i + 1, key);
                List<Map<String, Object>> rowList = new ArrayList<>();
                rowList.add(row);
                node.values.add(i + 1, rowList);
            }
        } else {
            while (i >= 0 && Values.compare(key, node.keys.get(i)) < 0) i--;
            i++;

            if (node.children.get(i).isFull()) {
                splitChild(node, i, node.children.get(i));
                if (Values.compare(key, node.keys.get(i)) > 0) i++;
            }
            insertNonFull(node.children.get(i), key, row);
        }
    }

    private void splitChild(BTreeNode parent, int i, BTreeNode child) {
        int t = BTreeNode.T;
        BTreeNode sibling = new BTreeNode(child.isLeaf);

        // Move upper half of keys to sibling
        parent.keys.add(i, child.keys.get(t - 1));
        if (!child.isLeaf) {
            parent.children.add(i + 1, sibling);
        } else {
            parent.children.add(i + 1, sibling);
        }

        // Copy keys t..2t-2 to sibling
        sibling.keys.addAll(child.keys.subList(t, child.keys.size()));

        if (child.isLeaf) {
            // Copy values too
            sibling.values.addAll(child.values.subList(t, child.values.size()));
            // Maintain leaf chain
            sibling.next = child.next;
            child.next = sibling;
            // Keep median in left node for leaf (B+ tree style)
            // Actually for B-tree we promote to parent, so remove from child
        } else {
            // Move children
            sibling.children.addAll(child.children.subList(t, child.children.size()));
            // Remove moved children from child
            child.children.subList(t, child.children.size()).clear();
        }

        if (child.isLeaf) {
            child.values.subList(t, child.values.size()).clear();
        }

        // Remove promoted key and upper keys from child
        child.keys.subList(t - 1, child.keys.size()).clear();
    }

    // ---- SEARCH (exact match) ----

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(Comparable key) {
        return search(root, key);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> search(BTreeNode node, Comparable key) {
        int i = 0;
        while (i < node.keys.size() && Values.compare(key, node.keys.get(i)) > 0) i++;

        if (i < node.keys.size() && Values.compare(key, node.keys.get(i)) == 0) {
            if (node.isLeaf) {
                return new ArrayList<>(node.values.get(i));
            }
            // For internal nodes in a B-tree, key is a separator.
            // We need to go to the right child to find the leaf.
            // But also check left child subtree.
            // Simplification: recurse into right child
            return search(node.children.get(i + 1), key);
        }

        if (node.isLeaf) return Collections.emptyList();
        return search(node.children.get(i), key);
    }

    // ---- RANGE SEARCH [low, high] ----

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> rangeSearch(Comparable low, Comparable high) {
        List<Map<String, Object>> result = new ArrayList<>();
        BTreeNode leaf = findLeaf(root, low);

        while (leaf != null) {
            for (int i = 0; i < leaf.keys.size(); i++) {
                Comparable k = leaf.keys.get(i);
                if (Values.compare(k, low) >= 0 && Values.compare(k, high) <= 0) {
                    result.addAll(leaf.values.get(i));
                } else if (Values.compare(k, high) > 0) {
                    return result;
                }
            }
            leaf = leaf.next;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private BTreeNode findLeaf(BTreeNode node, Comparable key) {
        if (node.isLeaf) return node;
        int i = 0;
        while (i < node.keys.size() && Values.compare(key, node.keys.get(i)) > 0) i++;
        return findLeaf(node.children.get(i), key);
    }
}
