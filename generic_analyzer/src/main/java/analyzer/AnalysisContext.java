package analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 解析コンテキスト。
 * 解析設定（パターン、対象名など）と結果を保持する。
 */
public class AnalysisContext {

    // 型使用調査用のパターン
    private Pattern typePattern;

    // 文字列リテラル調査用のパターン
    private Pattern stringLiteralPattern;

    // メソッド名・フィールド名のリスト
    private List<String> targetNames = new ArrayList<>();

    // アノテーション名のリスト
    private List<String> targetAnnotations = new ArrayList<>();

    // 解析結果の収集
    private final List<AnalysisResult> results = new ArrayList<>();

    // 統計情報
    private int totalFilesAnalyzed = 0;
    private int totalElementsScanned = 0;

    /**
     * 型使用調査用のパターンを設定する。
     *
     * @param pattern 正規表現パターン（例: "java\\.sql\\..*"）
     */
    public void setTypePattern(String pattern) {
        if (pattern != null && !pattern.isEmpty()) {
            this.typePattern = Pattern.compile(pattern);
        }
    }

    /**
     * 文字列リテラル調査用のパターンを設定する。
     *
     * @param pattern 正規表現パターン（例: "SELECT.*FROM.*"）
     */
    public void setStringLiteralPattern(String pattern) {
        if (pattern != null && !pattern.isEmpty()) {
            this.stringLiteralPattern = Pattern.compile(pattern);
        }
    }

    /**
     * メソッド名・フィールド名を追加する。
     *
     * @param names 対象名（カンマ区切り）
     */
    public void addTargetNames(String names) {
        if (names != null && !names.isEmpty()) {
            for (String name : names.split(",")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    targetNames.add(trimmed);
                }
            }
        }
    }

    /**
     * アノテーション名を追加する。
     *
     * @param annotations 対象アノテーション名（カンマ区切り）
     */
    public void addTargetAnnotations(String annotations) {
        if (annotations != null && !annotations.isEmpty()) {
            for (String annotation : annotations.split(",")) {
                String trimmed = annotation.trim();
                if (!trimmed.isEmpty()) {
                    // @プレフィックスを統一
                    if (trimmed.startsWith("@")) {
                        trimmed = trimmed.substring(1);
                    }
                    targetAnnotations.add(trimmed);
                }
            }
        }
    }

    /**
     * 型パターンに一致するか確認する。
     */
    public boolean matchesTypePattern(String typeName) {
        return typePattern != null && typePattern.matcher(typeName).matches();
    }

    /**
     * 文字列リテラルパターンに一致するか確認する。
     */
    public boolean matchesStringLiteralPattern(String value) {
        return stringLiteralPattern != null && stringLiteralPattern.matcher(value).find();
    }

    /**
     * 対象名リストに含まれるか確認する。
     */
    public boolean isTargetName(String name) {
        return targetNames.contains(name);
    }

    /**
     * 対象アノテーションリストに含まれるか確認する。
     */
    public boolean isTargetAnnotation(String annotationName) {
        // 完全修飾名または単純名でマッチ
        for (String target : targetAnnotations) {
            if (annotationName.equals(target) || annotationName.endsWith("." + target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析結果を追加する。
     */
    public void addResult(AnalysisResult result) {
        results.add(result);
    }

    /**
     * スキャンした要素数をインクリメント。
     */
    public void incrementElementsScanned() {
        totalElementsScanned++;
    }

    /**
     * 解析したファイル数をインクリメント。
     */
    public void incrementFilesAnalyzed() {
        totalFilesAnalyzed++;
    }

    // Getters
    public Pattern getTypePattern() {
        return typePattern;
    }

    public Pattern getStringLiteralPattern() {
        return stringLiteralPattern;
    }

    public List<String> getTargetNames() {
        return targetNames;
    }

    public List<String> getTargetAnnotations() {
        return targetAnnotations;
    }

    public List<AnalysisResult> getResults() {
        return results;
    }

    public int getTotalFilesAnalyzed() {
        return totalFilesAnalyzed;
    }

    public int getTotalElementsScanned() {
        return totalElementsScanned;
    }

    /**
     * 解析が有効か（少なくとも1つの設定があるか）を確認する。
     */
    public boolean hasAnyConfiguration() {
        return typePattern != null
                || stringLiteralPattern != null
                || !targetNames.isEmpty()
                || !targetAnnotations.isEmpty();
    }

    /**
     * 結果のサマリーを出力する。
     */
    public void printSummary() {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("解析完了");
        System.out.println("═".repeat(80));
        System.out.printf("解析ファイル数: %d%n", totalFilesAnalyzed);
        System.out.printf("スキャン要素数: %d%n", totalElementsScanned);
        System.out.printf("検出件数: %d%n", results.size());
        System.out.println("═".repeat(80));
    }

    /**
     * 全結果を出力する。
     */
    public void printResults() {
        if (results.isEmpty()) {
            System.out.println("\n検出結果はありませんでした。");
            return;
        }

        System.out.println("\n" + "═".repeat(80));
        System.out.println("検出結果");
        System.out.println("═".repeat(80));

        for (AnalysisResult result : results) {
            System.out.print(result);
        }
    }
}
