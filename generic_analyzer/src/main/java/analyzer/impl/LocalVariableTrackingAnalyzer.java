package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.Analyzer;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;
import analyzer.util.ConditionExtractor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 指定したローカル変数を追跡し、設定値と設定ルート（分岐条件）を抽出するアナライザ。
 */
public class LocalVariableTrackingAnalyzer implements Analyzer {

    private static final String CATEGORY = "Local Variable Tracking";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        if (context.getTrackLocalVariables() == null || context.getTrackLocalVariables().isEmpty()) {
            return;
        }

        if (element instanceof CtMethod<?>) {
            analyzeMethod((CtMethod<?>) element, context);
        }
    }

    private void analyzeMethod(CtMethod<?> method, AnalysisContext context) {
        List<String> trackVars = context.getTrackLocalVariables();

        // 1. ローカル変数の初期化を検出
        List<CtLocalVariable<?>> localVars = method.getElements(new TypeFilter<>(CtLocalVariable.class));
        for (CtLocalVariable<?> localVar : localVars) {
            String varName = localVar.getSimpleName();
            if (trackVars.contains(varName) && localVar.getDefaultExpression() != null) {
                processAssignment(localVar, varName, localVar.getDefaultExpression(), context);
            }
        }

        // 2. ローカル変数への代入を検出
        List<CtAssignment<?, ?>> assignments = method.getElements(new TypeFilter<>(CtAssignment.class));
        for (CtAssignment<?, ?> assignment : assignments) {
            CtExpression<?> assigned = assignment.getAssigned();
            if (assigned instanceof CtVariableAccess<?>) {
                String varName = ((CtVariableAccess<?>) assigned).getVariable().getSimpleName();
                if (trackVars.contains(varName)) {
                    processAssignment(assignment, varName, assignment.getAssignment(), context);
                }
            } else if (assigned != null) {
                // 配列要素への代入（例: arr[0] = ...）やフィールド代入（例: this.field = ...）など、
                // 単純な変数アクセス(CtVariableRead/Write)以外の式に対するフォールバック処理。
                // assigned.toString() の文字列表現が、利用者が指定した変数名と完全一致すれば対象とする。
                String varName = assigned.toString();
                if (trackVars.contains(varName)) {
                    processAssignment(assignment, varName, assignment.getAssignment(), context);
                }
            }
        }
    }

    private void processAssignment(CtElement statement, String varName, CtExpression<?> assignedValueExpr,
            AnalysisContext context) {
        String resolvedValue = resolveAssignedValue(assignedValueExpr);
        String routeConditions = ConditionExtractor.extractRouteConditions(statement);

        String matchedElement = String.format("ローカル変数設定: %s = %s", varName, resolvedValue);

        LocalVariableTrackingResult result = LocalVariableTrackingResult.fromElement(
                statement,
                CATEGORY,
                matchedElement,
                context,
                varName,
                resolvedValue,
                routeConditions);

        context.addResult(result);
    }

    private String resolveAssignedValue(CtExpression<?> expression) {
        if (expression == null) {
            return "null";
        }

        // 文字列リテラルの場合
        if (expression instanceof CtLiteral<?>) {
            Object value = ((CtLiteral<?>) expression).getValue();
            if (value instanceof String) {
                return "\"" + value + "\" (リテラル)";
            }
            return String.valueOf(value) + " (リテラル)";
        }

        // 定数フィールド参照の場合
        if (expression instanceof CtFieldRead<?>) {
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) expression;
            try {
                CtField<?> field = fieldRead.getVariable().getFieldDeclaration();
                if (field != null) {
                    CtExpression<?> defaultExpression = field.getDefaultExpression();
                    if (defaultExpression instanceof CtLiteral<?>) {
                        Object value = ((CtLiteral<?>) defaultExpression).getValue();
                        if (value instanceof String) {
                            return "\"" + value + "\" (定数: " + field.getSimpleName() + ")";
                        }
                        return String.valueOf(value) + " (定数: " + field.getSimpleName() + ")";
                    }
                }
            } catch (Exception e) {
                // 無視してフォールバック
            }
        }

        // 別のローカル変数の読み取り（初期値を追跡）
        if (expression instanceof CtVariableRead<?>) {
            CtVariableRead<?> varRead = (CtVariableRead<?>) expression;
            try {
                CtElement declaration = varRead.getVariable().getDeclaration();
                if (declaration instanceof CtLocalVariable<?>) {
                    CtLocalVariable<?> local = (CtLocalVariable<?>) declaration;
                    if (local.getDefaultExpression() instanceof CtLiteral<?>) {
                        Object value = ((CtLiteral<?>) local.getDefaultExpression()).getValue();
                        if (value instanceof String) {
                            return "\"" + value + "\" (変数初期値: " + local.getSimpleName() + ")";
                        }
                        return String.valueOf(value) + " (変数初期値: " + local.getSimpleName() + ")";
                    }
                }
            } catch (Exception e) {
                // 無視してフォールバック
            }
        }

        // メソッド呼び出しなどのステートメント
        return expression.toString() + " (ステートメント)";
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
