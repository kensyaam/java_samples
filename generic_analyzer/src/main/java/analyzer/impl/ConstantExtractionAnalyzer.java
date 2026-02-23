package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.Analyzer;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;

import java.util.regex.Pattern;

/**
 * 指定されたクラス・フィールドの正規表現にマッチする定数（static final）の初期設定値（リテラル）を抽出するアナライザ。
 */
public class ConstantExtractionAnalyzer implements Analyzer {

    private static final String CATEGORY = "定数定義";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        Pattern classPattern = context.getConstantClassPattern();
        Pattern fieldPattern = context.getConstantFieldPattern();

        // 抽出パターンが設定されていない場合は何もしない
        if (classPattern == null && fieldPattern == null) {
            return;
        }

        // 型定義（クラスやインターフェースなど）に限定して処理
        if (!(element instanceof CtType<?>)) {
            return;
        }

        CtType<?> type = (CtType<?>) element;
        String qualifiedName = type.getQualifiedName();

        // クラス名が正規表現にマッチするか確認
        if (classPattern != null && !classPattern.matcher(qualifiedName).matches()) {
            return;
        }

        // クラス内のフィールドを走査
        for (CtField<?> field : type.getFields()) {
            // static finalなフィールド(定数)のみ対象
            if (!field.isStatic() || !field.isFinal()) {
                continue;
            }

            String fieldName = field.getSimpleName();

            // フィールド名の正規表現にマッチするか確認
            if (fieldPattern != null && !fieldPattern.matcher(fieldName).matches()) {
                continue;
            }

            // 初期化式(デフォルト式)を取得
            CtElement defaultExpression = field.getDefaultExpression();
            if (defaultExpression != null) {
                String literalValue = defaultExpression.toString();

                // 文字列リテラルの場合、前後のダブルクォートを取り除く
                if (literalValue.startsWith("\"") && literalValue.endsWith("\"") && literalValue.length() >= 2) {
                    literalValue = literalValue.substring(1, literalValue.length() - 1);
                }

                // 検出内容のメッセージを作成（"HOGE = VALUE" のような形）
                String matchedElement = String.format("%s = %s", fieldName, literalValue);

                // Javadocの抽出
                String javadoc = field.getDocComment() != null ? field.getDocComment().trim() : "";

                ConstantExtractionResult result = ConstantExtractionResult.fromElement(
                        field,
                        CATEGORY,
                        matchedElement,
                        context,
                        fieldName,
                        literalValue,
                        javadoc);
                context.addResult(result);
            }
        }
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
