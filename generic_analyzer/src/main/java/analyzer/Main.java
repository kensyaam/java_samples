package analyzer;

import analyzer.impl.AnnotationAnalyzer;
import analyzer.impl.MethodOrFieldUsageAnalyzer;
import analyzer.impl.ReturnValueComparisonAnalyzer;
import analyzer.impl.StringLiteralAnalyzer;
import analyzer.impl.TypeUsageAnalyzer;
import analyzer.impl.LocalVariableTrackingAnalyzer;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spoon静的解析ツールのメインクラス。
 * CLIオプションを解析し、各Analyzerを使用してJavaソースを解析する。
 */
public class Main {

    // デフォルト値
    private static final int DEFAULT_COMPLIANCE_LEVEL = 21;
    private static final String DEFAULT_ENCODING = "UTF-8";
    private static final String DEFAULT_OUTPUT_CSV_ENCODING = "windows-31j";
    private static final String DEFAULT_FORMAT = "txt";

    public static void main(String[] args) {
        // ヘルプ表示
        if (args.length == 0 || containsHelp(args)) {
            printHelp();
            return;
        }

        // オプション解析
        List<String> sourceDirs = new ArrayList<>();
        List<String> classpathEntries = new ArrayList<>();
        int complianceLevel = DEFAULT_COMPLIANCE_LEVEL;
        String encoding = DEFAULT_ENCODING;

        // 解析設定
        String typePattern = null;
        String stringLiteralPattern = null;
        String targetNames = null;
        String targetAnnotations = null;
        String trackReturnMethods = null;
        String trackLocalVariables = null;

        // 出力設定
        String outputFile = null;
        String outputCsvEncoding = DEFAULT_OUTPUT_CSV_ENCODING;
        String format = DEFAULT_FORMAT;

        // オプション解析
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-s":
                case "--source":
                    if (i + 1 < args.length) {
                        sourceDirs.addAll(parseCommaSeparatedList(args[++i]));
                    }
                    break;
                case "-cp":
                case "--classpath":
                    if (i + 1 < args.length) {
                        classpathEntries.addAll(parseClasspathEntries(args[++i]));
                    }
                    break;
                case "-cl":
                case "--compliance":
                    if (i + 1 < args.length) {
                        complianceLevel = Integer.parseInt(args[++i]);
                    }
                    break;
                case "-e":
                case "--encoding":
                    if (i + 1 < args.length) {
                        encoding = args[++i];
                    }
                    break;
                case "-t":
                case "--type-pattern":
                    if (i + 1 < args.length) {
                        typePattern = args[++i];
                    }
                    break;
                case "-l":
                case "--literal-pattern":
                    if (i + 1 < args.length) {
                        stringLiteralPattern = args[++i];
                    }
                    break;
                case "-n":
                case "--names":
                    if (i + 1 < args.length) {
                        targetNames = args[++i];
                    }
                    break;
                case "-a":
                case "--annotations":
                    if (i + 1 < args.length) {
                        targetAnnotations = args[++i];
                    }
                    break;
                case "-o":
                case "--output":
                    if (i + 1 < args.length) {
                        outputFile = args[++i];
                    }
                    break;
                case "--output-csv-encoding":
                    if (i + 1 < args.length) {
                        outputCsvEncoding = args[++i];
                    }
                    break;
                case "-f":
                case "--format":
                    if (i + 1 < args.length) {
                        format = args[++i].toLowerCase();
                    }
                    break;
                case "--track-return":
                    if (i + 1 < args.length) {
                        trackReturnMethods = args[++i];
                    }
                    break;
                case "-v":
                case "--track-local-var":
                    if (i + 1 < args.length) {
                        trackLocalVariables = args[++i];
                    }
                    break;
                default:
                    // 不明なオプションは無視
                    break;
            }
        }

        // ソースディレクトリが指定されていない場合はエラー
        if (sourceDirs.isEmpty()) {
            System.err.println("エラー: ソースディレクトリを指定してください (-s オプション)");
            printHelp();
            return;
        }

        // 解析コンテキストの設定
        AnalysisContext context = new AnalysisContext();
        context.setTypePattern(typePattern);
        context.setStringLiteralPattern(stringLiteralPattern);
        context.addTargetNames(targetNames);
        context.addTargetAnnotations(targetAnnotations);
        context.addTrackReturnMethods(trackReturnMethods);
        context.addTrackLocalVariables(trackLocalVariables);

        // ソースディレクトリを追加（相対パス計算用）
        for (String sourceDir : sourceDirs) {
            context.addSourceDir(sourceDir);
        }

        // ソースファイルのエンコーディングを設定（コードスニペット取得時に使用）
        context.setSourceEncoding(Charset.forName(encoding));

        // 解析設定が何もない場合は警告
        if (!context.hasAnyConfiguration()) {
            System.out.println("警告: 解析パターンが指定されていません。");
            System.out.println("以下のオプションで解析対象を指定してください:");
            System.out.println("  -t: 型パターン (正規表現)");
            System.out.println("  -l: 文字列リテラルパターン (正規表現)");
            System.out.println("  -n: メソッド/フィールド名 (カンマ区切り)");
            System.out.println("  -a: アノテーション名 (カンマ区切り)");
            System.out.println("  --track-return: 戻り値追跡対象メソッド名 (カンマ区切り)");
            System.out.println("  -v, --track-local-var: ローカル変数追跡対象変数名 (カンマ区切り)");
            System.out.println();
        }

        // Spoon Launcherの設定
        Launcher launcher = new Launcher();

        // ソースディレクトリの追加
        for (String sourceDir : sourceDirs) {
            if (Files.exists(Path.of(sourceDir))) {
                launcher.addInputResource(sourceDir);
                System.out.println("ソースディレクトリ追加: " + sourceDir);
            } else {
                System.err.println("警告: ディレクトリが存在しません: " + sourceDir);
            }
        }

        // クラスパスの設定
        String[] cpArray = classpathEntries.toArray(new String[0]);
        if (cpArray.length > 0) {
            launcher.getEnvironment().setSourceClasspath(cpArray);
            System.out.println("クラスパス追加: " + String.join(", ", cpArray));
        }

        // 環境設定
        launcher.getEnvironment().setComplianceLevel(complianceLevel);
        launcher.getEnvironment().setEncoding(java.nio.charset.Charset.forName(encoding));
        launcher.getEnvironment().setNoClasspath(true); // レガシーコード対応
        launcher.getEnvironment().setAutoImports(false);
        launcher.getEnvironment().setCommentEnabled(true);

        System.out.printf("コンプライアンスレベル: %d%n", complianceLevel);
        System.out.printf("エンコーディング: %s%n", encoding);
        System.out.println();

        // モデルの構築
        System.out.println("ソースコードを解析中...");
        CtModel model = launcher.buildModel();

        // ファイル数のカウント
        model.getAllTypes().forEach(type -> context.incrementFilesAnalyzed());

        // オーケストレータの設定
        AnalysisOrchestrator orchestrator = new AnalysisOrchestrator(context);
        orchestrator.addAnalyzer(new TypeUsageAnalyzer());
        orchestrator.addAnalyzer(new MethodOrFieldUsageAnalyzer());
        orchestrator.addAnalyzer(new AnnotationAnalyzer());
        orchestrator.addAnalyzer(new StringLiteralAnalyzer());
        orchestrator.addAnalyzer(new ReturnValueComparisonAnalyzer());
        orchestrator.addAnalyzer(new LocalVariableTrackingAnalyzer());

        // 解析実行
        System.out.println("解析を実行中...");
        model.getRootPackage().accept(orchestrator);

        // 出力先の決定
        PrintWriter writer = null;
        boolean closeWriter = false;
        try {
            if (outputFile != null) {
                // ファイル出力
                Charset outputCharset = "csv".equals(format)
                        ? Charset.forName(outputCsvEncoding)
                        : Charset.forName("UTF-8");
                writer = new PrintWriter(
                        new OutputStreamWriter(
                                new FileOutputStream(outputFile), outputCharset));
                closeWriter = true;
                System.out.println("出力ファイル: " + outputFile + " (" + outputCharset.name() + ")");
            } else {
                // 標準出力
                writer = new PrintWriter(System.out, true);
            }

            // 結果出力
            if ("csv".equals(format)) {
                context.printResultsCsv(writer);
            } else {
                context.printResults(writer);
            }

            // サマリは常に標準出力へ
            context.printSummary();
        } catch (Exception e) {
            System.err.println("エラー: 出力中にエラーが発生しました: " + e.getMessage());
        } finally {
            if (closeWriter && writer != null) {
                writer.close();
            }
        }
    }

    /**
     * ヘルプオプションが含まれているか確認する。
     */
    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * カンマ区切りのリストを解析する。
     */
    private static List<String> parseCommaSeparatedList(String input) {
        List<String> result = new ArrayList<>();
        for (String item : input.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * クラスパスエントリを解析する。
     * ディレクトリが指定された場合は、そのディレクトリと直下のJARファイルを追加する。
     */
    private static List<String> parseClasspathEntries(String input) {
        List<String> result = new ArrayList<>();
        for (String entry : input.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            File file = new File(trimmed);
            if (!file.exists()) {
                System.err.println("警告: クラスパスエントリが存在しません: " + trimmed);
                continue;
            }

            if (file.isDirectory()) {
                // ディレクトリ自体を追加
                result.add(file.getAbsolutePath());

                // 直下のJARファイルを追加
                File[] jars = file.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        result.add(jar.getAbsolutePath());
                    }
                }
            } else {
                // ファイル（JAR）を追加
                result.add(file.getAbsolutePath());
            }
        }
        return result;
    }

    /**
     * ヘルプメッセージを出力する。
     */
    private static void printHelp() {
        System.out.println("Spoon Java Static Analyzer");
        System.out.println("==========================");
        System.out.println();
        System.out.println("使用方法: java -jar analyzer.jar [オプション]");
        System.out.println();
        System.out.println("必須オプション:");
        System.out.println("  -s, --source <dir,...>      解析対象のソースディレクトリ (カンマ区切りで複数指定可)");
        System.out.println();
        System.out.println("解析オプション (少なくとも1つを指定):");
        System.out.println("  -t, --type-pattern <regex>  型使用パターン (正規表現)");
        System.out.println("                              例: 'java\\.sql\\..*' (java.sqlパッケージ)");
        System.out.println("  -l, --literal-pattern <regex>");
        System.out.println("                              文字列リテラルパターン (正規表現)");
        System.out.println("                              例: 'SELECT.*FROM' (SQL文)");
        System.out.println("  -n, --names <name,...>      メソッド/フィールド名 (カンマ区切り)");
        System.out.println("                              例: 'executeQuery,executeUpdate'");
        System.out.println("  -a, --annotations <ann,...> アノテーション名 (カンマ区切り)");
        System.out.println("                              例: 'Deprecated,Override'");
        System.out.println("  --track-return <method,...>  戻り値追跡対象メソッド名 (カンマ区切り)");
        System.out.println("                              例: 'getStatus,getRole'");
        System.out.println("  -v, --track-local-var <var,...> ローカル変数追跡対象変数名 (カンマ区切り)");
        System.out.println("                              例: 'status,role'");
        System.out.println();
        System.out.println("環境オプション:");
        System.out.println("  -cp, --classpath <path,...> クラスパス (カンマ区切りで複数指定可)");
        System.out.println("                              ディレクトリを指定すると直下のJARも追加");
        System.out.println("  -cl, --compliance <level>   Javaコンプライアンスレベル (デフォルト: 21)");
        System.out.println("-e, --encoding <enc>        ソースエンコーディング (デフォルト: UTF-8)");
        System.out.println();
        System.out.println("出力オプション:");
        System.out.println("  -o, --output <file>         出力ファイル名 (省略時は標準出力)");
        System.out.println("  -f, --format <format>       出力フォーマット: txt, csv (デフォルト: txt)");
        System.out.println("  --output-csv-encoding <enc> CSV出力のエンコーディング (デフォルト: windows-31j)");
        System.out.println();
        System.out.println("例:");
        System.out.println("  # java.sqlパッケージの使用箇所を検索");
        System.out.println("  java -jar analyzer.jar -s src/main/java -t 'java\\.sql\\..*'");
        System.out.println();
        System.out.println("  # SQL文を含む文字列リテラルを検索");
        System.out.println("  java -jar analyzer.jar -s src -l 'SELECT.*FROM'");
        System.out.println();
        System.out.println("  # @Deprecatedアノテーションの使用箇所を検索");
        System.out.println("  java -jar analyzer.jar -s src -a Deprecated");
        System.out.println();
        System.out.println("  # 複数の解析を同時実行");
        System.out.println("  java -jar analyzer.jar -s src -t 'java\\.sql\\..*' -n executeQuery -l 'SELECT'");
        System.out.println();
        System.out.println("  # メソッド戻り値の比較値を追跡");
        System.out.println("  java -jar analyzer.jar -s src --track-return getStatus,getRole");
    }
}
