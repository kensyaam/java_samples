package analyzer.impl;

import analyzer.AnalysisContext;
import analyzer.AnalysisResult;
import spoon.reflect.declaration.CtElement;

/**
 * ローカル変数追跡の結果を保持するデータクラス。
 * 変数名、設定値、設定ルート（条件分岐）を追加で保持する。
 */
public class LocalVariableTrackingResult extends AnalysisResult {

    private final String variableName;
    private final String assignedValue;
    private final String routeConditions;

    public LocalVariableTrackingResult(String category, String fileName, int lineNumber,
            String scope, String codeSnippet, String matchedElement,
            String variableName, String assignedValue, String routeConditions) {
        super(category, fileName, lineNumber, scope, codeSnippet, matchedElement);
        this.variableName = variableName;
        this.assignedValue = assignedValue;
        this.routeConditions = routeConditions;
    }

    public static LocalVariableTrackingResult fromElement(CtElement element, String category,
            String matchedElement, AnalysisContext context,
            String variableName, String assignedValue, String routeConditions) {
        AnalysisResult base = AnalysisResult.fromElement(element, category, matchedElement, context);
        return new LocalVariableTrackingResult(
                base.getCategory(),
                base.getFileName(),
                base.getLineNumber(),
                base.getScope(),
                base.getCodeSnippet(),
                base.getMatchedElement(),
                variableName,
                assignedValue,
                routeConditions);
    }

    public String getVariableName() {
        return variableName;
    }

    public String getAssignedValue() {
        return assignedValue;
    }

    public String getRouteConditions() {
        return routeConditions;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        sb.append(String.format("  Variable: %s\n", variableName));
        sb.append(String.format("  Value: %s\n", assignedValue));
        sb.append(String.format("  Route: %s\n", routeConditions));
        return sb.toString();
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
                escapeCsv(variableName),
                escapeCsv(assignedValue),
                escapeCsv(routeConditions));
    }
}
