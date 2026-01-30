package analyzer;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    // ソースディレクトリのリスト（相対パス計算用）
    private List<Path> sourceDirs = new ArrayList<>();

    // 解析結果の収集
    private final List<AnalysisResult> results = new ArrayList<>();

    // 重複検出防止用のキーセット
    private final Set<String> detectedKeys = new HashSet<>();

    // ソースファイルのエンコーディング
    private Charset sourceEncoding = StandardCharsets.UTF_8;

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
     * ソースディレクトリを追加する。
     *
     * @param sourceDir ソースディレクトリのパス
     */
    public void addSourceDir(String sourceDir) {
        if (sourceDir != null && !sourceDir.isEmpty()) {
            sourceDirs.add(Path.of(sourceDir).toAbsolutePath().normalize());
        }
    }

    /**
     * ファイルパスからソースディレクトリを基準にした相対パスを計算する。
     * マッチするソースディレクトリがない場合はファイル名のみを返す。
     *
     * @param file ファイル
     * @return 相対パス（セパレータは/に統一）
     */
    public String getRelativePath(File file) {
        if (file == null) {
            return "Unknown";
        }
        Path filePath = file.toPath().toAbsolutePath().normalize();
        for (Path sourceDir : sourceDirs) {
            if (filePath.startsWith(sourceDir)) {
                // ソースディレクトリからの相対パスを計算
                Path relativePath = sourceDir.relativize(filePath);
                // Windowsのセパレータを/に統一
                return relativePath.toString().replace('\\', '/');
            }
        }
        // マッチしない場合はファイル名のみ
        return file.getName();
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
     * 重複（カテゴリ+ファイル名+行番号+検出内容が同一）は除外する。
     */
    public void addResult(AnalysisResult result) {
        // 重複チェック用のキーを生成（カテゴリ+ファイル名+行番号+検出内容）
        String key = result.getCategory() + ":" + result.getFileName() + ":"
                + result.getLineNumber() + ":" + result.getMatchedElement();
        if (isAlreadyDetected(key)) {
            return; // 重複は追加しない
        }
        markAsDetected(key);
        results.add(result);
    }

    /**
     * 指定されたキーが既に検出済みかどうかを確認する。
     *
     * @param key 検出キー（例: ファイル名:import名）
     * @return 検出済みの場合true
     */
    public boolean isAlreadyDetected(String key) {
        return detectedKeys.contains(key);
    }

    /**
     * 指定されたキーを検出済みとしてマークする。
     *
     * @param key 検出キー
     */
    public void markAsDetected(String key) {
        detectedKeys.add(key);
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

    public List<Path> getSourceDirs() {
        return sourceDirs;
    }

    /**
     * ソースファイルのエンコーディングを設定する。
     *
     * @param encoding エンコーディング
     */
    public void setSourceEncoding(Charset encoding) {
        if (encoding != null) {
            this.sourceEncoding = encoding;
        }
    }

    /**
     * ソースファイルのエンコーディングを取得する。
     *
     * @return エンコーディング
     */
    public Charset getSourceEncoding() {
        return sourceEncoding;
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
        System.out.println("\n" + "=".repeat(80));
        System.out.println("解析完了");
        System.out.println("=".repeat(80));
        System.out.printf("解析ファイル数: %d%n", totalFilesAnalyzed);
        System.out.printf("スキャン要素数: %d%n", totalElementsScanned);
        System.out.printf("検出件数: %d%n", results.size());
        System.out.println("=".repeat(80));
    }

    /**
     * 全結果をテキスト形式で出力する。
     *
     * @param writer 出力先
     */
    public void printResults(PrintWriter writer) {
        if (results.isEmpty()) {
            writer.println("\n検出結果はありませんでした。");
            return;
        }

        writer.println("\n" + "=".repeat(80));
        writer.println("検出結果");
        writer.println("=".repeat(80));

        for (AnalysisResult result : results) {
            writer.print(result);
        }
        writer.flush();
    }

    /**
     * 全結果をCSV形式で出力する。
     *
     * @param writer 出力先
     */
    public void printResultsCsv(PrintWriter writer) {
        // ヘッダー出力
        writer.println("ファイル名,行番号,スコープ,カテゴリ,検出内容,コードスニペット");

        for (AnalysisResult result : results) {
            writer.println(result.toCsvLine());
        }
        writer.flush();
    }
}
