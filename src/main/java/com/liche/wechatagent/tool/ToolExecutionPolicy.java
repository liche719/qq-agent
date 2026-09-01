package com.liche.wechatagent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolExecutionPolicy {
    ToolExecutionClass value() default ToolExecutionClass.FAST;

    boolean retryable() default true;

    boolean hasSideEffect() default false;

    boolean destructive() default false;

    boolean requiresConfirmation() default false;

    String confirmationParameter() default "";

    boolean allowParallel() default true;

    ToolRiskLevel riskLevel() default ToolRiskLevel.LOW;
}
