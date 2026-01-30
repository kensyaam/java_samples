package analyzer;

import spoon.reflect.declaration.CtCompilationUnit;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtImport;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.code.CtStatement;
import spoon.reflect.reference.CtExecutableReference;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.stream.Collectors;

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
     * @param context        解析コンテキスト（相対パス計算に使用）
     * @return 解析結果
     */
    public static AnalysisResult fromElement(CtElement element, String category,
            String matchedElement, AnalysisContext context) {
        // ファイル名の取得（ソースディレクトリからの相対パス）
        // 要素のpositionが無効な場合は親要素から取得を試みる
        String fileName = getFileName(element, context);

        // 行番号の取得（要素のpositionが無効な場合は親要素から取得を試みる）
        int lineNumber = getLineNumber(element);

        // スコープの取得（クラス#メソッド）
        String scope = getScope(element);

        // ソースファイルから対象行をコードスニペットとして取得（エンコーディング指定）
        String codeSnippet = getCodeSnippet(element, lineNumber, context.getSourceEncoding());

        return new AnalysisResult(category, fileName, lineNumber, scope, codeSnippet, matchedElement);
    }

    /**
     * 要素からファイル名を取得する。
     * 要素のpositionが無効な場合は親要素から取得を試みる。
     */
    private static String getFileName(CtElement element, AnalysisContext context) {
        // まず要素自身のpositionを確認
        if (element.getPosition() != null && element.getPosition().isValidPosition()
                && element.getPosition().getFile() != null) {
            return context.getRelativePath(element.getPosition().getFile());
        }

        // 親のCtType（クラス）から取得を試みる
        CtType<?> parentType = element.getParent(CtType.class);
        if (parentType != null && parentType.getPosition() != null
                && parentType.getPosition().isValidPosition()
                && parentType.getPosition().getFile() != null) {
            return context.getRelativePath(parentType.getPosition().getFile());
        }

        return "Unknown";
    }

    /**
     * 要素から行番号を取得する。
     * 要素のpositionが無効な場合は親要素から取得を試みる。
     */
    private static int getLineNumber(CtElement element) {
        // まず要素自身のpositionを確認
        if (element.getPosition() != null && element.getPosition().isValidPosition()) {
            return element.getPosition().getLine();
        }

        // 親のStatement（式文など）から取得を試みる
        CtStatement parentStatement = element.getParent(CtStatement.class);
        if (parentStatement != null && parentStatement.getPosition() != null
                && parentStatement.getPosition().isValidPosition()) {
            return parentStatement.getPosition().getLine();
        }

        // 親のメソッドから取得を試みる
        CtMethod<?> parentMethod = element.getParent(CtMethod.class);
        if (parentMethod != null && parentMethod.getPosition() != null
                && parentMethod.getPosition().isValidPosition()) {
            return parentMethod.getPosition().getLine();
        }

        return 0;
    }

    /**
     * 要素が属するスコープ（クラス#メソッド(引数型)）を取得する。
     */
    private static String getScope(CtElement element) {
        // 親メソッドを探す
        CtMethod<?> parentMethod = element.getParent(CtMethod.class);
        if (parentMethod != null) {
            // メソッドの場合は完全修飾名シグネチャを返す
            return getMethodSignature(parentMethod);
        }

        // 要素自身がCtTypeの場合
        if (element instanceof CtType<?>) {
            return ((CtType<?>) element).getQualifiedName();
        }

        // 親クラスを探す
        CtType<?> parentType = element.getParent(CtType.class);
        if (parentType != null) {
            return parentType.getQualifiedName();
        }

        // Import文の場合はCompilationUnitからメインタイプを取得
        if (element instanceof CtImport) {
            CtCompilationUnit cu = element.getPosition().getCompilationUnit();
            if (cu != null && cu.getMainType() != null) {
                return cu.getMainType().getQualifiedName();
            }
        }

        return "(top-level)";
    }

    /**
     * 型名を簡略化（Java標準ライブラリのパッケージ名を省略）
     */
    private static String simplifyTypeName(String typeName) {
        if (typeName == null)
            return "";

        // 配列の処理
        int arrayDim = 0;
        while (typeName.endsWith("[]")) {
            arrayDim++;
            typeName = typeName.substring(0, typeName.length() - 2);
        }

        // Java標準ライブラリのパッケージ省略
        if (typeName.startsWith("java.lang.")) {
            typeName = typeName.substring("java.lang.".length());
        } else if (typeName.startsWith("java.util.")) {
            typeName = typeName.substring("java.util.".length());
        } else if (typeName.startsWith("java.io.")) {
            typeName = typeName.substring("java.io.".length());
        }

        // 配列記号を復元
        for (int i = 0; i < arrayDim; i++) {
            typeName += "[]";
        }

        return typeName;
    }

    /**
     * メソッドシグネチャを取得（完全修飾名）
     */
    private static String getMethodSignature(CtMethod<?> method) {
        CtType<?> declaringType = method.getDeclaringType();
        String className = declaringType != null ? declaringType.getQualifiedName() : "Unknown";
        String methodName = method.getSimpleName();

        List<String> params = method.getParameters().stream()
                .map(p -> simplifyTypeName(p.getType().getQualifiedName()))
                .collect(Collectors.toList());

        return className + "#" + methodName + "(" + String.join(", ", params) + ")";
    }

    /**
     * 実行可能参照からシグネチャを取得
     */
    private static String getExecutableSignature(CtExecutableReference<?> executable) {
        CtExecutable<?> declaration = executable.getExecutableDeclaration();

        // 宣言が取得でき、かつCtMethodの場合
        if (declaration != null && declaration instanceof CtMethod<?>) {
            return getMethodSignature((CtMethod<?>) declaration);
        }

        // CtMethodでない場合(コンストラクタなど)や取得できない場合のフォールバック
        String className = executable.getDeclaringType().getQualifiedName();
        String methodName = executable.getSimpleName();

        List<String> params = executable.getParameters().stream()
                .map(p -> simplifyTypeName(p.getQualifiedName()))
                .collect(Collectors.toList());

        return className + "#" + methodName + "(" + String.join(", ", params) + ")";
    }

    /**
     * コードスニペットを取得する。
     * ソースファイルから直接対象行を読み取る。
     *
     * @param element    対象要素
     * @param lineNumber 行番号
     * @param encoding   ソースファイルのエンコーディング
     */
    private static String getCodeSnippet(CtElement element, int lineNumber, Charset encoding) {
        // ファイルと行番号が無効な場合はフォールバック
        File file = getSourceFile(element);
        if (file == null || lineNumber <= 0) {
            return getCodeSnippetFallback(element);
        }

        // ソースファイルから対象行を読み取る（エンコーディングを指定）
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), encoding))) {
            String line;
            int currentLine = 0;
            while ((line = reader.readLine()) != null) {
                currentLine++;
                if (currentLine == lineNumber) {
                    return line;
                }
            }
        } catch (IOException e) {
            // 読み取り失敗時はフォールバック
        }
        return getCodeSnippetFallback(element);
    }

    /**
     * 要素からソースファイルを取得する。
     */
    private static File getSourceFile(CtElement element) {
        if (element.getPosition() != null && element.getPosition().isValidPosition()
                && element.getPosition().getFile() != null) {
            return element.getPosition().getFile();
        }
        // 親のCtTypeから取得を試みる
        CtType<?> parentType = element.getParent(CtType.class);
        if (parentType != null && parentType.getPosition() != null
                && parentType.getPosition().isValidPosition()
                && parentType.getPosition().getFile() != null) {
            return parentType.getPosition().getFile();
        }
        return null;
    }

    /**
     * フォールバック用のコードスニペット取得（親Statementから取得）。
     */
    private static String getCodeSnippetFallback(CtElement element) {
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
        return String.format("%s,%d,%s,%s,%s,%s",
                escapeCsv(fileName),
                lineNumber,
                escapeCsv(scope),
                escapeCsv(category),
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
