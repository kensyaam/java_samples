package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.Analyzer;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtDo;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtWhile;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Collections;
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
        String routeConditions = extractRouteConditions(statement);

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

    private String extractRouteConditions(CtElement assignment) {
        List<String> conditions = new ArrayList<>();
        // ASTを上(親)に向かって辿るためのカレントノード
        CtElement current = assignment;

        while (current != null && !(current instanceof CtMethod)) {
            CtElement parent = current.getParent();

            if (parent instanceof CtIf) {
                CtIf ifStmt = (CtIf) parent;

                // 代入文が then ブロック（条件が真の場合のルート）に含まれているか
                if (isDescendant(ifStmt.getThenStatement(), current)) {
                    boolean isElseIf = false;

                    // Spoon ASTでは `else if` は「`else` ブロックの内側にある `if`文」として表現される。
                    // そのため、親（祖父）要素が `CtIf` であり、かつ自分がその `else` ブロック内にいるかを判定する。
                    CtElement grandparent = ifStmt.getParent();
                    if (grandparent instanceof CtBlock && ((CtBlock<?>) grandparent).isImplicit()) {
                        grandparent = grandparent.getParent();
                    }
                    if (grandparent instanceof CtIf) {
                        CtIf parentIf = (CtIf) grandparent;
                        if (isDescendant(parentIf.getElseStatement(), ifStmt)) {
                            isElseIf = true;
                        }
                    }

                    if (isElseIf) {
                        conditions.add("else if (" + ifStmt.getCondition() + ")");
                    } else {
                        conditions.add("if (" + ifStmt.getCondition() + ")");
                    }
                }
                // 代入文が else ブロック（条件が偽の場合のルート）に含まれているか
                else if (isDescendant(ifStmt.getElseStatement(), current)) {
                    boolean skipElse = false;

                    // 下から辿ってきた要素(current)自体が if文 である場合、
                    // それは上記の logic で「else if」として既に抽出済み。
                    // したがって、重複して「else (親の条件)」と抽出するのを防ぐ。
                    if (current instanceof CtIf) {
                        skipElse = true;
                    } else if (current instanceof CtBlock && ((CtBlock<?>) current).isImplicit()
                            && !((CtBlock<?>) current).getStatements().isEmpty()
                            && ((CtBlock<?>) current).getStatement(0) instanceof CtIf) {
                        skipElse = true;
                    }

                    if (!skipElse) {
                        conditions.add("else (" + ifStmt.getCondition() + ")");
                    }
                }
            } else if (parent instanceof CtCatch) {
                CtCatch catchStmt = (CtCatch) parent;
                conditions.add("catch (" + catchStmt.getParameter().getType() + ")");
            } else if (parent instanceof CtCase) {
                CtCase<?> caseStmt = (CtCase<?>) parent;
                String switchExprStr = "";

                // 親階層が CtSwitch の場合は、switchに渡されている式（変数）を取得する
                CtElement switchParent = caseStmt.getParent();
                if (switchParent instanceof CtSwitch) {
                    CtExpression<?> switchExpr = ((CtSwitch<?>) switchParent).getSelector();
                    if (switchExpr != null) {
                        switchExprStr = switchExpr.toString() + " == ";
                    }
                }

                List<String> caseExprs = new ArrayList<>();

                // switch文におけるフォールスルー（上のcaseから流れてきた場合）に対応するため、
                // 同じ switch ブロック内の、自分より前にある case 文で文(statements)を持たないものを収集する。
                if (switchParent instanceof CtSwitch) {
                    CtSwitch<?> switchBlock = (CtSwitch<?>) switchParent;
                    List<CtCase<?>> cases = new ArrayList<>();
                    for (CtCase<?> c : switchBlock.getCases()) {
                        cases.add(c);
                    }
                    int currentIndex = cases.indexOf(caseStmt);

                    // 現在の case より前の case を遡る
                    for (int i = currentIndex - 1; i >= 0; i--) {
                        CtCase<?> prevCase = cases.get(i);
                        if (prevCase.getStatements().isEmpty()) {
                            // 前から追加していくことで元の定義順序を維持する
                            List<String> prevStrs = new ArrayList<>();
                            if (prevCase.getCaseExpressions().isEmpty()) {
                                prevStrs.add("default");
                            } else {
                                prevCase.getCaseExpressions().forEach(expr -> prevStrs.add(expr.toString()));
                            }
                            caseExprs.addAll(0, prevStrs);
                        } else {
                            break;
                        }
                    }
                }

                // 自分自身の case 条件を追加
                if (caseStmt.getCaseExpressions().isEmpty()) {
                    caseExprs.add("default");
                } else {
                    caseStmt.getCaseExpressions().forEach(expr -> caseExprs.add(expr.toString()));
                }

                conditions.add("case " + switchExprStr + String.join(", ", caseExprs));
            } else if (parent instanceof CtFor) {
                CtFor forStmt = (CtFor) parent;
                String expr = forStmt.getExpression() != null ? forStmt.getExpression().toString() : "";
                conditions.add("for (" + expr + ")");
            } else if (parent instanceof CtWhile) {
                conditions.add("while (" + ((CtWhile) parent).getLoopingExpression() + ")");
            } else if (parent instanceof CtDo) {
                conditions.add("do-while (" + ((CtDo) parent).getLoopingExpression() + ")");
            }

            current = parent;
        }

        if (conditions.isEmpty()) {
            return "条件なし (スコープ直下)";
        }

        // トップレベルから下への順序（外側から内側）にするため、リストを反転
        Collections.reverse(conditions);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append("  ".repeat(i)).append(conditions.get(i));
        }
        return sb.toString();
    }

    private boolean isDescendant(CtElement root, CtElement target) {
        if (root == null || target == null)
            return false;
        if (root == target)
            return true;

        CtElement current = target.getParent();
        while (current != null) {
            if (current == root)
                return true;
            current = current.getParent();
        }
        return false;
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
