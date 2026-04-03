package analyzer.impl;

import analyzer.AnalysisResult;

/**
 * クラス依存関係の解析結果を保持するクラス。
 * CSV出力時に「元クラス」「先クラス」「依存関係タイプ」を個別カラムとして出力する。
 */
public class ClassDependencyResult extends AnalysisResult {

    private final String fromClass;
    private final String toClass;
    private final String dependencyType;

    public ClassDependencyResult(String category, String fileName, int lineNumber,
            String scope, String matchedElement, String codeSnippet,
            String fromClass, String toClass, String dependencyType) {
        super(category, fileName, lineNumber, scope, matchedElement, codeSnippet);
        this.fromClass = fromClass;
        this.toClass = toClass;
        this.dependencyType = dependencyType;
    }

    @Override
    public String getCsvHeader() {
        return "ファイル名,行番号,スコープ,カテゴリ,検出内容,コードスニペット,元クラス,先クラス,依存関係タイプ";
    }

    @Override
    public String toCsvLine() {
        return String.format("%s,%d,%s,%s,%s,%s,%s,%s,%s",
                escapeCsv(getFileName()),
                getLineNumber(),
                escapeCsv(getScope()),
                escapeCsv(getCategory()),
                escapeCsv(getMatchedElement()),
                escapeCsv(getCodeSnippet()),
                escapeCsv(fromClass),
                escapeCsv(toClass),
                escapeCsv(dependencyType));
    }
}
