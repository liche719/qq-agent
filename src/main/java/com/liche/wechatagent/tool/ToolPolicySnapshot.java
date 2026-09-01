package com.liche.wechatagent.tool;

/** Immutable runtime view of the execution constraints declared by a tool. */
public record ToolPolicySnapshot(
        ToolExecutionClass executionClass,
        boolean retryable,
        boolean hasSideEffect,
        boolean destructive,
        boolean requiresConfirmation,
        String confirmationParameter,
        boolean allowParallel,
        ToolRiskLevel riskLevel) {

    public static ToolPolicySnapshot defaults() {
        return new ToolPolicySnapshot(ToolExecutionClass.FAST, true, false, false, false, "", true, ToolRiskLevel.LOW);
    }

    public static ToolPolicySnapshot from(ToolExecutionPolicy policy) {
        if (policy == null) {
            return defaults();
        }
        return new ToolPolicySnapshot(policy.value(), policy.retryable(), policy.hasSideEffect(),
                policy.destructive(), policy.requiresConfirmation(), policy.confirmationParameter(),
                policy.allowParallel(), policy.riskLevel());
    }
}
