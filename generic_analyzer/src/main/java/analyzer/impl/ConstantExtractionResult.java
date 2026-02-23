package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.declaration.CtElement;

/**
 * 定数抽出の解析結果を保持するクラス。
 * CSV出力時に個別のカラムとして定数名と値を出力する。
 */
public class ConstantExtractionResult extends AnalysisResult {

    private final String constantName;
    private final String constantValue;
    private final String javadoc;

    public ConstantExtractionResult(String category, String fileName, int lineNumber, String scope, String codeSnippet,
            String matchedElement, String constantName, String constantValue, String javadoc) {
        super(category, fileName, lineNumber, scope, codeSnippet, matchedElement);
        this.constantName = constantName;
        this.constantValue = constantValue;
        this.javadoc = javadoc;
    }

    public static ConstantExtractionResult fromElement(CtElement element, String category,
            String matchedElement, AnalysisContext context,
            String constantName, String constantValue, String javadoc) {
        AnalysisResult base = AnalysisResult.fromElement(element, category, matchedElement, context);
        return new ConstantExtractionResult(base.getCategory(), base.getFileName(), base.getLineNumber(),
                base.getScope(), base.getCodeSnippet(), base.getMatchedElement(), constantName, constantValue, javadoc);
    }

    @Override
    public String getCsvHeader() {
        return "ファイル名,行番号,スコープ,カテゴリ,検出内容,コードスニペット,定数名,値,javadoc";
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
                escapeCsv(constantName),
                escapeCsv(constantValue),
                escapeCsv(javadoc));
    }
}
