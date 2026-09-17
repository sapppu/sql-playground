package com.sqlplayground.engine.util;

/**
 * Central comparison semantics for engine values.
 *
 * SQL columns and literals arrive with mixed boxed numeric types
 * (e.g. a DOUBLE column compared against a LONG literal). Raw
 * {@code Comparable.compareTo} calls across those types throw
 * {@code ClassCastException}, so every ordering site in the engine
 * (B-Tree, stats, executor) must go through here instead of casting.
 */
public final class Values {

    private Values() { }

    /**
     * Total order over engine values:
     * null sorts before everything; numbers compare by double value
     * regardless of boxed type; same-type comparables use compareTo;
     * anything else falls back to case-insensitive string comparison.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static int compare(Object a, Object b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(
                ((Number) a).doubleValue(),
                ((Number) b).doubleValue());
        }
        if (a instanceof Comparable && b instanceof Comparable
                && a.getClass().isAssignableFrom(b.getClass())) {
            return ((Comparable) a).compareTo(b);
        }
        return a.toString().compareToIgnoreCase(b.toString());
    }

    public static boolean equalValues(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.toString().equalsIgnoreCase(b.toString());
    }
}
