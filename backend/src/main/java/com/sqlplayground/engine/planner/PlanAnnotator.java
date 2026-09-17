package com.sqlplayground.engine.planner;

import com.sqlplayground.engine.executor.QueryExecutor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stamps plan nodes with the actual rows each stage produced.
 * The planner runs before execution, so estimates and actuals meet here.
 */
public final class PlanAnnotator {

    private PlanAnnotator() { }

    /**
     * Matches profile stages to plan nodes by operation, consuming each
     * stage once in order. Executor-side JOIN entries match either join
     * node type. Nodes without a matching entry keep actualRows null.
     * A planned-vs-executed strategy disagreement is recorded explicitly
     * as {@code actual_strategy} instead of silently.
     */
    public static void annotate(QueryPlanner.PlanNode plan,
                                List<QueryExecutor.StageCount> profile) {
        if (plan == null || profile == null || profile.isEmpty()) return;
        Map<String, List<QueryExecutor.StageCount>> byOp = new LinkedHashMap<>();
        for (QueryExecutor.StageCount stage : profile) {
            String key = "JOIN".equals(stage.operation) ? "JOIN" : stage.operation;
            byOp.computeIfAbsent(key, k -> new ArrayList<>()).add(stage);
        }
        walk(plan, byOp, new HashMap<>());
    }

    private static void walk(QueryPlanner.PlanNode node,
                             Map<String, List<QueryExecutor.StageCount>> byOp,
                             Map<String, Integer> cursor) {
        String op = node.getOperation();
        String key = ("HASH_JOIN".equals(op) || "NESTED_LOOP_JOIN".equals(op)) ? "JOIN" : op;
        List<QueryExecutor.StageCount> stages = byOp.get(key);
        if (stages != null) {
            int i = cursor.getOrDefault(key, 0);
            if (i < stages.size()) {
                QueryExecutor.StageCount stage = stages.get(i);
                cursor.put(key, i + 1);
                node.setActualRows(stage.actualRows);
                if ("JOIN".equals(key) && stage.strategy != null) {
                    Object planned = node.getStats().get("strategy");
                    if (planned != null && !planned.equals(stage.strategy)) {
                        try {
                            node.getStats().put("actual_strategy", stage.strategy);
                        } catch (UnsupportedOperationException ignored) {
                            // Stats map is immutable (shouldn't happen for SELECT nodes)
                        }
                    }
                }
            }
        }
        for (QueryPlanner.PlanNode child : node.getChildren()) {
            walk(child, byOp, cursor);
        }
    }
}
