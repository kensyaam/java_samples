package analyzer;

import spoon.reflect.declaration.CtElement;

/**
 * 解析処理の共通インターフェース。
 * 各Analyzerはこのインターフェースを実装し、特定の要素を検出する。
 */
public interface Analyzer {

    /**
     * 要素を解析し、条件に一致する場合は結果をコンテキストに追加する。
     *
     * @param element 解析対象の要素
     * @param context 解析コンテキスト（設定と結果収集）
     */
    void analyze(CtElement element, AnalysisContext context);

    /**
     * このAnalyzerが検出するカテゴリ名を返す。
     *
     * @return カテゴリ名（例: "Type Usage", "Method Call"）
     */
    String getCategory();
}
