package analyzer;

import spoon.reflect.declaration.CtElement;
import spoon.reflect.visitor.CtScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析オーケストレータ。
 * CtScannerを継承し、1回の走査で全てのAnalyzerを実行する。
 * 暗黙的要素（Implicit）は検出対象外とする。
 */
public class AnalysisOrchestrator extends CtScanner {

    private final List<Analyzer> analyzers = new ArrayList<>();
    private final AnalysisContext context;

    /**
     * コンストラクタ。
     *
     * @param context 解析コンテキスト
     */
    public AnalysisOrchestrator(AnalysisContext context) {
        this.context = context;
    }

    /**
     * Analyzerを登録する。
     *
     * @param analyzer 登録するAnalyzer
     */
    public void addAnalyzer(Analyzer analyzer) {
        analyzers.add(analyzer);
    }

    /**
     * 全要素をスキャンする際に呼ばれるフック。
     * 暗黙的要素を除外し、登録された全Analyzerで解析する。
     */
    @Override
    public void scan(CtElement element) {
        if (element == null) {
            return;
        }

        // 暗黙的要素（ソースコード上に実体がない）は除外
        if (element.isImplicit()) {
            // 暗黙的要素でも子要素はスキャンする
            super.scan(element);
            return;
        }

        // 統計情報の更新
        context.incrementElementsScanned();

        // 全Analyzerで解析
        for (Analyzer analyzer : analyzers) {
            analyzer.analyze(element, context);
        }

        // 子要素のスキャンを継続
        super.scan(element);
    }

    /**
     * 解析コンテキストを取得する。
     */
    public AnalysisContext getContext() {
        return context;
    }
}
