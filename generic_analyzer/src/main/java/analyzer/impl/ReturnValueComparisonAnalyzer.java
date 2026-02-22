package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 戻り値比較の調査Analyzer。
 * 指定メソッドの戻り値を格納した変数を追跡し、
 * equals()やswitch文で比較されている値を抽出する。
 *
 * 対応パターン:
 * - var.equals("literal") / var.equals(CONSTANT)
 * - "literal".equals(var)（逆パターン）
 * - switch(var) { case "A": ... }
 * - if-else / else-if チェーン
 * - switch文のdefaultケース
 * - 定数の場合は定義元の文字列リテラルまで解決
 */
public class ReturnValueComparisonAnalyzer implements Analyzer {

    private static final String CATEGORY = "Return Value Comparison";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // 追跡対象メソッドが未設定の場合はスキップ
        if (context.getTrackReturnMethods().isEmpty()) {
            return;
        }

        // CtMethodを受け取ったとき、メソッド全体を解析する
        if (element instanceof CtMethod<?>) {
            analyzeMethod((CtMethod<?>) element, context);
        }
    }

    /**
     * メソッド全体を解析し、対象メソッドの戻り値変数を収集して比較を検出する。
     */
    private void analyzeMethod(CtMethod<?> method, AnalysisContext context) {
        // パス1: 対象メソッドの戻り値を格納している変数を収集
        Map<String, CtInvocation<?>> trackedVariables = collectTrackedVariables(method, context);
        if (trackedVariables.isEmpty()) {
            return;
        }

        // パス2: 追跡対象変数に対するequals()比較を検出
        detectEqualsComparisons(method, trackedVariables, context);

        // パス3: 追跡対象変数に対するswitch文を検出
        detectSwitchComparisons(method, trackedVariables, context);
    }

    /**
     * パス1: 対象メソッドの戻り値を格納している変数を収集する。
     * CtLocalVariable（ローカル変数宣言）とCtAssignment（代入文）を走査。
     *
     * @return 変数名 → 呼び出し元のCtInvocation のマップ
     */
    private Map<String, CtInvocation<?>> collectTrackedVariables(
            CtMethod<?> method, AnalysisContext context) {
        Map<String, CtInvocation<?>> tracked = new HashMap<>();

        // ローカル変数宣言: String result = service.getStatus();
        List<CtLocalVariable<?>> localVars = method.getElements(new TypeFilter<>(CtLocalVariable.class));
        for (CtLocalVariable<?> localVar : localVars) {
            CtExpression<?> defaultExpr = localVar.getDefaultExpression();
            if (defaultExpr instanceof CtInvocation<?>) {
                CtInvocation<?> invocation = (CtInvocation<?>) defaultExpr;
                if (isTargetInvocation(invocation, context)) {
                    tracked.put(localVar.getSimpleName(), invocation);
                }
            }
        }

        // 代入文: result = service.getStatus();
        List<CtAssignment<?, ?>> assignments = method.getElements(new TypeFilter<>(CtAssignment.class));
        for (CtAssignment<?, ?> assignment : assignments) {
            CtExpression<?> assigned = assignment.getAssignment();
            if (assigned instanceof CtInvocation<?>) {
                CtInvocation<?> invocation = (CtInvocation<?>) assigned;
                if (isTargetInvocation(invocation, context)) {
                    String varName = assignment.getAssigned().toString();
                    tracked.put(varName, invocation);
                }
            }
        }

        return tracked;
    }

    /**
     * 呼び出しが追跡対象メソッドかどうかを判定する。
     */
    private boolean isTargetInvocation(CtInvocation<?> invocation, AnalysisContext context) {
        if (invocation.getExecutable() == null) {
            return false;
        }

        String simpleName = invocation.getExecutable().getSimpleName();
        String declaringType = "";
        if (invocation.getExecutable().getDeclaringType() != null) {
            declaringType = invocation.getExecutable().getDeclaringType().getQualifiedName();
        }
        String qualifiedName = declaringType.isEmpty()
                ? simpleName
                : declaringType + "." + simpleName;

        return context.isTrackReturnMethod(simpleName, qualifiedName);
    }

    /**
     * パス2: 追跡対象変数に対するequals()呼び出しを検出する。
     * 正順パターン: var.equals(X)
     * 逆パターン: "literal".equals(var) / CONSTANT.equals(var)
     */
    private void detectEqualsComparisons(
            CtMethod<?> method,
            Map<String, CtInvocation<?>> trackedVariables,
            AnalysisContext context) {

        List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));

        for (CtInvocation<?> invocation : invocations) {
            if (invocation.getExecutable() == null) {
                continue;
            }
            if (!"equals".equals(invocation.getExecutable().getSimpleName())) {
                continue;
            }
            if (invocation.getArguments().size() != 1) {
                continue;
            }

            CtExpression<?> target = invocation.getTarget();
            CtExpression<?> argument = invocation.getArguments().get(0);

            String matchedVarName = null;
            CtExpression<?> comparedValue = null;

            // 正順パターン: var.equals(X)
            if (target instanceof CtVariableRead<?>) {
                String varName = ((CtVariableRead<?>) target).getVariable().getSimpleName();
                if (trackedVariables.containsKey(varName)) {
                    matchedVarName = varName;
                    comparedValue = argument;
                }
            }

            // 逆パターン: "literal".equals(var) / CONSTANT.equals(var)
            if (matchedVarName == null && argument instanceof CtVariableRead<?>) {
                String argVarName = ((CtVariableRead<?>) argument).getVariable().getSimpleName();
                if (trackedVariables.containsKey(argVarName)) {
                    matchedVarName = argVarName;
                    comparedValue = target;
                }
            }

            if (matchedVarName == null || comparedValue == null) {
                continue;
            }

            // 比較値を解決
            String resolvedValue = resolveComparedValue(comparedValue);

            // else分岐の種類を判定（else if / else / なし）
            String elseBranchType = getElseBranchType(invocation);

            // 検出結果を生成
            CtInvocation<?> originalInvocation = trackedVariables.get(matchedVarName);
            String methodCallStr = formatMethodCall(originalInvocation);
            String matchedElement = String.format(
                    "変数: %s = %s → 比較値: %s [%s]",
                    matchedVarName,
                    methodCallStr,
                    resolvedValue,
                    elseBranchType);

            AnalysisResult result = AnalysisResult.fromElement(
                    invocation, CATEGORY, matchedElement, context);
            context.addResult(result);
        }
    }

    /**
     * パス3: 追跡対象変数に対するswitch文を検出する。
     */
    private void detectSwitchComparisons(
            CtMethod<?> method,
            Map<String, CtInvocation<?>> trackedVariables,
            AnalysisContext context) {

        List<CtSwitch<?>> switches = method.getElements(new TypeFilter<>(CtSwitch.class));

        for (CtSwitch<?> switchStmt : switches) {
            CtExpression<?> selector = switchStmt.getSelector();
            if (!(selector instanceof CtVariableRead<?>)) {
                continue;
            }

            String varName = ((CtVariableRead<?>) selector).getVariable().getSimpleName();
            if (!trackedVariables.containsKey(varName)) {
                continue;
            }

            // case値を収集
            List<String> caseValues = new ArrayList<>();
            boolean hasDefault = false;

            for (CtCase<?> caseStmt : switchStmt.getCases()) {
                List<? extends CtExpression<?>> caseExpressions = caseStmt.getCaseExpressions();
                if (caseExpressions.isEmpty()) {
                    // defaultケース
                    hasDefault = true;
                } else {
                    for (CtExpression<?> caseExpr : caseExpressions) {
                        caseValues.add(resolveComparedValue(caseExpr));
                    }
                }
            }

            // 検出結果を生成
            CtInvocation<?> originalInvocation = trackedVariables.get(varName);
            String methodCallStr = formatMethodCall(originalInvocation);
            StringJoiner caseJoiner = new StringJoiner(", ");
            for (String value : caseValues) {
                caseJoiner.add(value);
            }

            String matchedElement = String.format(
                    "変数: %s = %s → switch case: %s [%s]",
                    varName,
                    methodCallStr,
                    caseJoiner.toString(),
                    hasDefault ? "defaultあり" : "defaultなし");

            AnalysisResult result = AnalysisResult.fromElement(
                    switchStmt, CATEGORY, matchedElement, context);
            context.addResult(result);
        }
    }

    /**
     * 比較値を文字列表現に解決する。
     * - リテラルの場合: "value" (リテラル)
     * - 定数フィールドの場合: "value" (定数: ClassName.FIELD_NAME) ※解決可能な場合
     * - その他: 式の文字列表現
     */
    private String resolveComparedValue(CtExpression<?> expression) {
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
            String fieldName = fieldRead.getVariable().getSimpleName();
            String declaringType = "";
            if (fieldRead.getVariable().getDeclaringType() != null) {
                declaringType = fieldRead.getVariable().getDeclaringType().getSimpleName();
            }
            String constantRef = declaringType.isEmpty()
                    ? fieldName
                    : declaringType + "." + fieldName;

            // 定数の定義内容（文字列リテラル）を解決
            String resolvedLiteral = resolveConstantValue(fieldRead);
            if (resolvedLiteral != null) {
                return "\"" + resolvedLiteral + "\" (定数: " + constantRef + ")";
            }
            return constantRef + " (定数・値未解決)";
        }

        // その他の式
        return expression.toString();
    }

    /**
     * 定数フィールドの定義値（文字列リテラル）を解決する。
     *
     * @param fieldRead フィールド読み取り式
     * @return 解決できた場合は文字列リテラルの値、できない場合はnull
     */
    private String resolveConstantValue(CtFieldRead<?> fieldRead) {
        try {
            // フィールド宣言を取得
            CtField<?> field = fieldRead.getVariable().getFieldDeclaration();
            if (field == null) {
                return null;
            }

            // デフォルト値（初期化式）を取得
            CtExpression<?> defaultExpression = field.getDefaultExpression();
            if (defaultExpression instanceof CtLiteral<?>) {
                Object value = ((CtLiteral<?>) defaultExpression).getValue();
                if (value instanceof String) {
                    return (String) value;
                }
            }
        } catch (Exception e) {
            // NoClasspath環境などで解決できない場合は無視
        }
        return null;
    }

    /**
     * equals()呼び出しの親にif文があり、else分岐の種類を判定する。
     * Spoonでは else if の場合、getElseStatement() が返す CtBlock の
     * isImplicit() が true になる。
     *
     * @return "else if分岐あり" / "else分岐あり" / "else分岐なし"
     */
    private String getElseBranchType(CtElement element) {
        // 直近のCtIfを探す
        CtElement current = element.getParent();
        while (current != null) {
            if (current instanceof CtIf) {
                CtIf ifStmt = (CtIf) current;
                CtStatement elseStmt = ifStmt.getElseStatement();
                if (elseStmt == null) {
                    return "else分岐なし";
                }
                // else if の場合: elseStatement が implicit な CtBlock に包まれている
                if (elseStmt instanceof CtBlock<?> && ((CtBlock<?>) elseStmt).isImplicit()) {
                    return "else if分岐あり";
                }
                // 純粋な else ブロック
                return "else分岐あり";
            }
            // CtMethod に到達したら探索終了
            if (current instanceof CtMethod) {
                break;
            }
            current = current.getParent();
        }
        return "else分岐なし";
    }

    /**
     * メソッド呼び出しのフォーマット文字列を生成する。
     * 例: "service.getStatus()" や "getStatus()"
     */
    private String formatMethodCall(CtInvocation<?> invocation) {
        String methodName = invocation.getExecutable().getSimpleName();
        CtExpression<?> target = invocation.getTarget();
        if (target != null && !target.isImplicit()) {
            return target.toString() + "." + methodName + "()";
        }
        return methodName + "()";
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
