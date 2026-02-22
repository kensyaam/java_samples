package analyzer.util;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtDo;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFor;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtWhile;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分岐条件を抽出するためのユーティリティクラス
 */
public class ConditionExtractor {

    /**
     * 指定されたステートメントからメソッド定義に至るまでの分岐条件をリストとして抽出します。
     * リストの先頭が一番外側の条件になります。
     *
     * @param statement 起点となるステートメント
     * @return 分岐条件のリスト
     */
    public static List<String> extractConditionList(CtElement statement) {
        List<String> conditions = new ArrayList<>();
        // ASTを上(親)に向かって辿るためのカレントノード
        CtElement current = statement;

        while (current != null && !(current instanceof CtMethod)) {
            CtElement parent = current.getParent();

            if (parent instanceof CtIf) {
                CtIf ifStmt = (CtIf) parent;

                // 代入文などが then ブロック（条件が真の場合のルート）に含まれているか
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
                // 代入文などが else ブロック（条件が偽の場合のルート）に含まれているか
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

        // トップレベルから下への順序（外側から内側）にするため、リストを反転
        Collections.reverse(conditions);
        return conditions;
    }

    /**
     * 指定されたステートメントからメソッド定義に至るまでの分岐条件を抽出します。
     * （複数行にフォーマットされて返却されます。条件がない場合は「条件なし」の文字列を返します。）
     *
     * @param statement 起点となるステートメント
     * @return 分岐条件の文字列表現
     */
    public static String extractRouteConditions(CtElement statement) {
        List<String> conditions = extractConditionList(statement);

        if (conditions.isEmpty()) {
            return "条件なし (スコープ直下)";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append("  ".repeat(i)).append(conditions.get(i));
        }
        return sb.toString();
    }

    /**
     * targetがrootの部分木（子孫）であるかどうかを判定します。
     */
    public static boolean isDescendant(CtElement root, CtElement target) {
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
}
