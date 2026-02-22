package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.declaration.CtElement;

/**
 * 呼び出しルート追跡の解析結果を保持するクラス。
 * CSV出力時に個別のカラムとしてルートの情報を出力する。
 */
public class CallTrackingResult extends AnalysisResult {

    private final String routeSummary;
    private final String entryPoints;

    public CallTrackingResult(String category, String fileName, int lineNumber, String scope, String codeSnippet,
            String matchedElement, String routeSummary, String entryPoints) {
        super(category, fileName, lineNumber, scope, codeSnippet, matchedElement);
        this.routeSummary = routeSummary;
        this.entryPoints = entryPoints;
    }

    public static CallTrackingResult fromElement(CtElement element, String category,
            String matchedElement, AnalysisContext context,
            String routeSummary, String entryPoints) {
        AnalysisResult base = AnalysisResult.fromElement(element, category, matchedElement, context);
        return new CallTrackingResult(base.getCategory(), base.getFileName(), base.getLineNumber(),
                base.getScope(), base.getCodeSnippet(), base.getMatchedElement(), routeSummary, entryPoints);
    }

    @Override
    public String getCsvHeader() {
        return "ファイル名,行番号,スコープ,カテゴリ,検出内容,コードスニペット,呼び出しルートと分岐条件,到達エントリーポイント";
    }

    @Override
    public String toCsvLine() {
        return String.format("%s,%d,%s,%s,%s,%s,%s,%s",
                escapeCsv(getFileName()),
                getLineNumber(),
                escapeCsv(getScope()),
                escapeCsv(getCategory()),
                escapeCsv(getMatchedElement()),
                escapeCsv(getCodeSnippet()),
                escapeCsv(routeSummary),
                escapeCsv(entryPoints));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        sb.append("  Route:\n").append(routeSummary).append("\n");
        sb.append("  Entry Points:\n").append(entryPoints).append("\n");
        return sb.toString();
    }
}
