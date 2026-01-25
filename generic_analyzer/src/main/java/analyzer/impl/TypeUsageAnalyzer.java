package analyzer.impl;

import analyzer.Analyzer;
import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.reference.CtTypeReference;

/**
 * 型使用の調査Analyzer。
 * 正規表現で指定されたパッケージやクラスを使用している箇所を特定する。
 * 対象: 変数宣言、継承、キャスト、ジェネリクスなど
 */
public class TypeUsageAnalyzer implements Analyzer {

    private static final String CATEGORY = "Type Usage";

    @Override
    public void analyze(CtElement element, AnalysisContext context) {
        // 型パターンが設定されていない場合はスキップ
        if (context.getTypePattern() == null) {
            return;
        }

        // CtTypeReferenceのみを対象とする
        if (!(element instanceof CtTypeReference<?>)) {
            return;
        }

        CtTypeReference<?> typeRef = (CtTypeReference<?>) element;

        // 完全修飾名を取得
        String qualifiedName = typeRef.getQualifiedName();
        if (qualifiedName == null || qualifiedName.isEmpty()) {
            return;
        }

        // パターンマッチ
        if (context.matchesTypePattern(qualifiedName)) {
            AnalysisResult result = AnalysisResult.fromElement(
                    element,
                    CATEGORY,
                    qualifiedName);
            context.addResult(result);
        }
    }

    @Override
    public String getCategory() {
        return CATEGORY;
    }
}
