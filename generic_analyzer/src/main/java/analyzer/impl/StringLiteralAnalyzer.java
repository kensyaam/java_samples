package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.declaration.CtElement;

/**
 * 文字列リテラルの調査Analyzer。
 * 正規表現で指定された文字列リテラルが含まれている箇所を特定する。
 */
public class StringLiteralAnalyzer implements Analyzer {

    private static final String CATEGORY = "String Literal";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // 文字列リテラルパターンが設定されていない場合はスキップ
        if (context.getStringLiteralPattern() == null) {
            return;
        }

        // CtLiteralのみを対象とする
        if (!(element instanceof CtLiteral<?>)) {
            return;
        }

        CtLiteral<?> literal = (CtLiteral<?>) element;
        Object value = literal.getValue();

        // 文字列リテラルのみを対象とする
        if (!(value instanceof String)) {
            return;
        }

        String stringValue = (String) value;

        // パターンマッチ
        if (context.matchesStringLiteralPattern(stringValue)) {
            // 長い文字列は切り詰める
            String displayValue = stringValue;
            if (displayValue.length() > 80) {
                displayValue = displayValue.substring(0, 77) + "...";
            }
            // 改行を可視化
            displayValue = displayValue.replace("\n", "\\n").replace("\r", "\\r");

            AnalysisResult result = AnalysisResult.fromElement(
                    element,
                    CATEGORY,
                    "\"" + displayValue + "\"",
                    context);
            context.addResult(result);
        }
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
