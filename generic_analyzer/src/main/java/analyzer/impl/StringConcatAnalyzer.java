package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.code.*;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;

/**
 * 文字列結合抽出機能。
 * 文字列の+演算子結合およびStringBuilder/StringBufferのappendにおいて、
 * null値が"null"文字列として結合されるリスクのある箇所を抽出する。
 */
public class StringConcatAnalyzer implements Analyzer {

    private static final String CATEGORY = "String Concatenation";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        if (!context.isCheckStringConcat()) {
            return;
        }

        try {
            // パターンA: +演算子 (CtBinaryOperator)
            if (element instanceof CtBinaryOperator) {
                CtBinaryOperator<?> op = (CtBinaryOperator<?>) element;
                if (op.getKind() == BinaryOperatorKind.PLUS) {
                    // 親が+演算子ではない（＝最上位の+演算子のみをチェック対象とする）
                    if (!isParentPlusOperator(op)) {
                        processBinaryOperator(op, context);
                    }
                }
            }
            // パターンB: StringBuilder/StringBuffer の append (CtInvocation)
            else if (element instanceof CtInvocation) {
                CtInvocation<?> invocation = (CtInvocation<?>) element;
                if (isStringBuilderAppend(invocation)) {
                    processStringBuilderAppend(invocation, context);
                }
            }
        } catch (Exception e) {
            // 堅牢性のための例外処理
            System.err.println("StringConcatAnalyzerで解析エラーが発生しました: " + e.getMessage());
        }
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    // =========================================================================
    // 判定ユーティリティ
    // =========================================================================

    private boolean isParentPlusOperator(CtBinaryOperator<?> op) {
        CtElement parent = op.getParent();
        if (parent instanceof CtBinaryOperator) {
            return ((CtBinaryOperator<?>) parent).getKind() == BinaryOperatorKind.PLUS;
        }
        return false;
    }

    private boolean isStringBuilderAppend(CtInvocation<?> invocation) {
        if (invocation.getExecutable() == null) {
            return false;
        }
        // メソッド名が append
        if (!"append".equals(invocation.getExecutable().getSimpleName())) {
            return false;
        }
        // ターゲットの型が StringBuilder または StringBuffer
        CtExpression<?> target = invocation.getTarget();
        if (target != null && target.getType() != null) {
            String qName = target.getType().getQualifiedName();
            if ("java.lang.StringBuilder".equals(qName) || "java.lang.StringBuffer".equals(qName)) {
                return true;
            }
        }
        // noClasspath等で型が解決できない場合のフォールバック:
        // ターゲット式を文字列として見たときに StringBuilder か StringBuffer のインスタンス化や変数名に近いか
        if (target != null) {
            String targetStr = target.toString();
            if (targetStr.contains("StringBuilder") || targetStr.contains("StringBuffer")
                    || targetStr.matches(".*\\bsb\\b.*") || targetStr.matches(".*\\bbuffer\\b.*")) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // パターンA (+演算子) の処理
    // =========================================================================

    private void processBinaryOperator(CtBinaryOperator<?> op, AnalysisContext context) {
        List<CtExpression<?>> leaves = getLeafOperands(op);

        // 1. 文字列結合であること (少なくとも1つの葉がString型または文字列リテラル)
        boolean hasString = false;
        for (CtExpression<?> leaf : leaves) {
            if (isStringOrStringLiteral(leaf)) {
                hasString = true;
                break;
            }
        }
        if (!hasString) {
            return;
        }

        // 2. 例外メッセージの構築ではないこと
        if (isExceptionCreation(op)) {
            return;
        }

        // 3. 定数結合の除外処理
        if (context.isExcludePartialConstants()) {
            // どちらか一方が定数の場合に除外 (定数が1つでも含まれていれば除外)
            boolean hasConstant = false;
            for (CtExpression<?> leaf : leaves) {
                if (isConstant(leaf)) {
                    hasConstant = true;
                    break;
                }
            }
            if (hasConstant) {
                return;
            }
        } else {
            // 定数のみの結合を除外 (すべてが定数の場合のみ除外)
            boolean allConstants = true;
            for (CtExpression<?> leaf : leaves) {
                if (!isConstant(leaf)) {
                    allConstants = false;
                    break;
                }
            }
            if (allConstants) {
                return;
            }
        }

        // 4. 危険な（nullになり得る）参照型が少なくとも1つ含まれること
        // かつ、例外関連(e.getMessage()等)や安全な三項演算子ではないこと
        boolean hasDangerousOperand = false;
        for (CtExpression<?> leaf : leaves) {
            if (!isConstant(leaf) && isNullableReferenceType(leaf) && !isExceptionRelated(leaf) && !isSafeTernary(leaf)) {
                hasDangerousOperand = true;
                break;
            }
        }

        if (hasDangerousOperand) {
            String snippet = op.toString();
            if (snippet.length() > 100) {
                snippet = snippet.substring(0, 97) + "...";
            }
            AnalysisResult result = AnalysisResult.fromElement(
                    op,
                    CATEGORY,
                    "Potential null string concat: " + snippet,
                    context
            );
            context.addResult(result);
        }
    }

    // =========================================================================
    // パターンB (StringBuilder.append) の処理
    // =========================================================================

    private void processStringBuilderAppend(CtInvocation<?> invocation, AnalysisContext context) {
        List<CtExpression<?>> arguments = invocation.getArguments();
        if (arguments.isEmpty()) {
            return;
        }
        CtExpression<?> arg = arguments.get(0);

        // 1. 例外メッセージの構築ではないこと
        if (isExceptionCreation(invocation)) {
            return;
        }

        // 2. 定数ではないこと
        if (isConstant(arg)) {
            return;
        }

        // 3. 危険な（nullになり得る）参照型であること
        // かつ、例外関連(e.getMessage()等)や安全な三項演算子ではないこと
        if (isNullableReferenceType(arg) && !isExceptionRelated(arg) && !isSafeTernary(arg)) {
            String snippet = invocation.toString();
            if (snippet.length() > 100) {
                snippet = snippet.substring(0, 97) + "...";
            }
            AnalysisResult result = AnalysisResult.fromElement(
                    invocation,
                    CATEGORY,
                    "Potential null append: " + snippet,
                    context
            );
            context.addResult(result);
        }
    }

    // =========================================================================
    // 判定ロジック詳細
    // =========================================================================

    private List<CtExpression<?>> getLeafOperands(CtExpression<?> expr) {
        List<CtExpression<?>> leaves = new ArrayList<>();
        collectLeafOperands(expr, leaves);
        return leaves;
    }

    private void collectLeafOperands(CtExpression<?> expr, List<CtExpression<?>> leaves) {
        if (expr instanceof CtBinaryOperator) {
            CtBinaryOperator<?> op = (CtBinaryOperator<?>) expr;
            if (op.getKind() == BinaryOperatorKind.PLUS) {
                collectLeafOperands(op.getLeftHandOperand(), leaves);
                collectLeafOperands(op.getRightHandOperand(), leaves);
                return;
            }
        }
        leaves.add(expr);
    }

    private boolean isStringOrStringLiteral(CtExpression<?> expr) {
        if (expr instanceof CtLiteral) {
            return ((CtLiteral<?>) expr).getValue() instanceof String;
        }
        if (expr.getType() != null) {
            return "java.lang.String".equals(expr.getType().getQualifiedName());
        }
        return false;
    }

    private boolean isConstant(CtExpression<?> expr) {
        if (expr instanceof CtLiteral) {
            return true;
        }
        if (expr instanceof CtFieldRead) {
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) expr;
            var varRef = fieldRead.getVariable();
            if (varRef != null) {
                var decl = varRef.getDeclaration();
                if (decl != null) {
                    return decl.isStatic() && decl.isFinal();
                } else {
                    // noClasspath用のフォールバック：定数名が大文字スネークケース
                    return varRef.getSimpleName().matches("^[A-Z0-9_]+$");
                }
            }
        }
        return false;
    }

    private boolean isNullableReferenceType(CtExpression<?> expr) {
        if (expr.getType() == null) {
            // 型不明の場合は安全側に倒して「nullになり得る参照型」とみなす
            return true;
        }
        // プリミティブ型はnullになり得ない
        return !expr.getType().isPrimitive();
    }

    private boolean isExceptionCreation(CtElement element) {
        // 祖先に throw 文があるか
        if (element.getParent(CtThrow.class) != null) {
            return true;
        }
        // 祖先に例外クラスのコンストラクタ呼び出しがあるか
        CtConstructorCall<?> parentCons = element.getParent(CtConstructorCall.class);
        if (parentCons != null && parentCons.getType() != null) {
            CtTypeReference<?> typeRef = parentCons.getType();
            if (typeRef.isSubtypeOf(element.getFactory().Type().createReference("java.lang.Throwable"))) {
                return true;
            }
            // フォールバック: クラス名に Exception/Error/Throwable が含まれるか
            String name = typeRef.getSimpleName();
            if (name.contains("Exception") || name.contains("Error") || name.contains("Throwable")) {
                return true;
            }
        }
        return false;
    }

    private boolean isExceptionRelated(CtExpression<?> expr) {
        // 1. 式自身の型が Throwable のサブクラスである場合 (例: exception オブジェクト自体の結合)
        if (expr.getType() != null) {
            CtTypeReference<?> typeRef = expr.getType();
            if (typeRef.isSubtypeOf(expr.getFactory().Type().createReference("java.lang.Throwable"))) {
                return true;
            }
            // フォールバック: クラス名判定
            String name = typeRef.getSimpleName();
            if (name.contains("Exception") || name.contains("Error") || name.contains("Throwable")) {
                return true;
            }
        }

        // 2. メソッド呼び出しで、ターゲット（レシーバ）の型が Throwable のサブクラスである場合 (例: e.getMessage())
        if (expr instanceof CtInvocation) {
            CtInvocation<?> inv = (CtInvocation<?>) expr;
            CtExpression<?> target = inv.getTarget();
            if (target != null && target.getType() != null) {
                CtTypeReference<?> targetType = target.getType();
                if (targetType.isSubtypeOf(expr.getFactory().Type().createReference("java.lang.Throwable"))) {
                    return true;
                }
                String name = targetType.getSimpleName();
                if (name.contains("Exception") || name.contains("Error") || name.contains("Throwable")) {
                    return true;
                }
            }
            // フォールバック: 変数名が e や ex などの例外オブジェクトっぽいターゲットに対する getMessage などの呼び出し
            if (target != null) {
                String targetStr = target.toString();
                if (targetStr.matches("^e[x]?$")) {
                    String method = inv.getExecutable().getSimpleName();
                    if (method.contains("Message") || "toString".equals(method) || "getLocalizedMessage".equals(method)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSafeTernary(CtExpression<?> expr) {
        // 三項演算子 (CtConditional) の処理
        if (expr instanceof CtConditional) {
            CtConditional<?> cond = (CtConditional<?>) expr;
            CtExpression<?> thenExpr = cond.getThenExpression();
            CtExpression<?> elseExpr = cond.getElseExpression();

            // then または else が null リテラルの場合は危険
            if (isNullLiteral(thenExpr) || isNullLiteral(elseExpr)) {
                return false;
            }

            // 条件式に null チェック (== null や != null) が含まれているか簡易チェック
            CtExpression<Boolean> condition = cond.getCondition();
            String condStr = condition.toString();
            if (condStr.contains("null") && (condStr.contains("==") || condStr.contains("!="))) {
                return true; // nullチェックをして代替値を返しているため安全とみなす
            }
        }
        return false;
    }

    private boolean isNullLiteral(CtExpression<?> expr) {
        if (expr instanceof CtLiteral) {
            return ((CtLiteral<?>) expr).getValue() == null;
        }
        return false;
    }
}
