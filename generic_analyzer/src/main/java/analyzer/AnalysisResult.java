package analyzer;

import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.code.CtStatement;

/**
 * 解析結果を保持するデータクラス。
 * 検出した要素の情報（カテゴリ、場所、スコープ、コードスニペット）を格納する。
 */
public class AnalysisResult {

    private final String category;
    private final String fileName;
    private final int lineNumber;
    private final String scope;
    private final String codeSnippet;
    private final String matchedElement;

    /**
     * コンストラクタ。
     *
     * @param category       検出カテゴリ
     * @param fileName       ファイル名
     * @param lineNumber     行番号
     * @param scope          スコープ（クラス#メソッド）
     * @param codeSnippet    コードスニペット
     * @param matchedElement マッチした要素の説明
     */
    public AnalysisResult(String category, String fileName, int lineNumber,
            String scope, String codeSnippet, String matchedElement) {
        this.category = category;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.scope = scope;
        this.codeSnippet = codeSnippet;
        this.matchedElement = matchedElement;
    }

    /**
     * CtElementから解析結果を生成するファクトリメソッド。
     *
     * @param element        検出した要素
     * @param category       検出カテゴリ
     * @param matchedElement マッチした要素の説明
     * @return 解析結果
     */
    public static AnalysisResult fromElement(CtElement element, String category, String matchedElement) {
        // ファイル名の取得
        String fileName = "Unknown";
        if (element.getPosition() != null && element.getPosition().isValidPosition()) {
            fileName = element.getPosition().getFile() != null
                    ? element.getPosition().getFile().getName()
                    : "Unknown";
        }

        // 行番号の取得
        int lineNumber = element.getPosition() != null && element.getPosition().isValidPosition()
                ? element.getPosition().getLine()
                : 0;

        // スコープの取得（クラス#メソッド）
        String scope = getScope(element);

        // 親Statementからコードスニペットを取得
        String codeSnippet = getCodeSnippet(element);

        return new AnalysisResult(category, fileName, lineNumber, scope, codeSnippet, matchedElement);
    }

    /**
     * 要素が属するスコープ（クラス#メソッド）を取得する。
     */
    private static String getScope(CtElement element) {
        StringBuilder scope = new StringBuilder();

        // 親クラスを探す
        CtType<?> parentType = element.getParent(CtType.class);
        if (parentType != null) {
            scope.append(parentType.getQualifiedName());
        }

        // 親メソッドを探す
        CtMethod<?> parentMethod = element.getParent(CtMethod.class);
        if (parentMethod != null) {
            // メソッドの場合はQualifiedName（シグネチャ）をセット
            return parentMethod.getSignature();
        }

        return scope.length() > 0 ? scope.toString() : "(top-level)";
    }

    /**
     * 親Statementからコードスニペットを取得する。
     */
    private static String getCodeSnippet(CtElement element) {
        // 親のStatementを探す
        CtStatement parentStatement = element.getParent(CtStatement.class);
        if (parentStatement != null) {
            String code = parentStatement.toString();
            // 長すぎる場合は切り詰める
            if (code.length() > 200) {
                code = code.substring(0, 197) + "...";
            }
            // 改行を除去して1行にする
            return code.replaceAll("\\s+", " ").trim();
        }
        // Statementがない場合は要素自体を返す
        String code = element.toString();
        if (code.length() > 200) {
            code = code.substring(0, 197) + "...";
        }
        return code.replaceAll("\\s+", " ").trim();
    }

    // Getters
    public String getCategory() {
        return category;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getScope() {
        return scope;
    }

    public String getCodeSnippet() {
        return codeSnippet;
    }

    public String getMatchedElement() {
        return matchedElement;
    }

    /**
     * 結果をフォーマットして出力する。
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("─".repeat(80)).append("\n");
        sb.append(String.format("【%s】 %s\n", category, matchedElement));
        sb.append(String.format("  File: %s : %d\n", fileName, lineNumber));
        sb.append(String.format("  Scope: %s\n", scope));
        sb.append(String.format("  Code: %s\n", codeSnippet));
        return sb.toString();
    }

    /**
     * CSV形式の1行を返す。
     * ダブルクォートとカンマをエスケープする。
     *
     * @return CSV形式の文字列
     */
    public String toCsvLine() {
        return String.format("%s,%s,%d,%s,%s,%s",
                escapeCsv(category),
                escapeCsv(fileName),
                lineNumber,
                escapeCsv(scope),
                escapeCsv(matchedElement),
                escapeCsv(codeSnippet));
    }

    /**
     * CSV用にフィールドをエスケープする。
     */
    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        // ダブルクォート、カンマ、改行を含む場合はダブルクォートで囲む
        if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
