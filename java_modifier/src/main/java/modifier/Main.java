package modifier;

import modifier.processor.AnnotationProcessor;
import modifier.processor.ClassRelocationProcessor;
import modifier.processor.FqcnToImportProcessor;
import modifier.processor.ImportReplacementProcessor;
import modifier.processor.ParameterRemovalProcessor;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Javaコード編集ツール (java_modifier) のエントリポイント。
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0 || containsHelp(args)) {
            printHelp();
            return;
        }

        ModifierContext context = new ModifierContext();

        // コマンドライン引数の解析
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-s":
                case "--source":
                    if (i + 1 < args.length) {
                        for (String dir : parseCommaSeparatedList(args[++i])) {
                            context.addSourceDir(dir);
                        }
                    }
                    break;
                case "-d":
                case "--destination":
                    if (i + 1 < args.length) {
                        context.setDestinationDir(args[++i]);
                    }
                    break;
                case "-cp":
                case "--classpath":
                    if (i + 1 < args.length) {
                        for (String cp : parseClasspathEntries(args[++i])) {
                            context.addClasspathEntry(cp);
                        }
                    }
                    break;
                case "-cl":
                case "--compliance":
                    if (i + 1 < args.length) {
                        context.setComplianceLevel(Integer.parseInt(args[++i]));
                    }
                    break;
                case "-e":
                case "--encoding":
                    if (i + 1 < args.length) {
                        context.setEncoding(Charset.forName(args[++i]));
                    }
                    break;
                case "--newline":
                    if (i + 1 < args.length) {
                        context.setNewline(args[++i]);
                    }
                    break;
                case "--replace-import":
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].split(":");
                        if (parts.length == 2) {
                            context.setReplaceImport(parts[0], parts[1]);
                        }
                    }
                    break;
                case "--relocate-class":
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].split(":");
                        if (parts.length == 2) {
                            context.setRelocateClass(parts[0], parts[1]);
                        }
                    }
                    break;
                case "--remove-param":
                    if (i + 1 < args.length) {
                        context.setRemoveParamFqcn(args[++i]);
                    }
                    break;
                case "--fqcn-to-import":
                    if (i + 1 < args.length) {
                        context.setFqcnToImportRegex(args[++i]);
                    }
                    break;
                case "--add-annotation-field":
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].split(":");
                        if (parts.length == 2) {
                            context.setAddAnnotationField(parts[0], parts[1]);
                        }
                    }
                    break;
                case "--add-annotation-type":
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].split(":");
                        if (parts.length == 2) {
                            context.setAddAnnotationType(parts[0], parts[1]);
                        }
                    }
                    break;
                case "--replace-annotation":
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].split(":");
                        if (parts.length == 2) {
                            context.setReplaceAnnotation(parts[0], parts[1]);
                        }
                    }
                    break;
                case "--remove-annotation":
                    if (i + 1 < args.length) {
                        context.setRemoveAnnotationRegex(args[++i]);
                    }
                    break;
                case "--add-annotation-by-annotation":
                    if (i + 1 < args.length) {
                        String[] parts = args[++i].split(":");
                        if (parts.length == 2) {
                            context.setAddAnnotationByAnnotation(parts[0], parts[1]);
                        }
                    }
                    break;
            }
        }

        if (context.getSourceDirs().isEmpty()) {
            System.err.println("エラー: ソースディレクトリを指定してください (-s オプション)");
            return;
        }

        if (!context.hasAnyAction()) {
            System.err.println("警告: 変換オプションが一つも指定されていません。");
        }

        System.out.println("Spoon環境を初期化中...");
        Launcher launcher = EnvironmentFactory.createLauncher(context);

        System.out.println("モデルをビルド中...");
        CtModel model = launcher.buildModel();

        System.out.println("変換プロセスを実行中...");
        // 処理の実行
        if (context.getReplaceImportOldPrefix() != null) {
            new ImportReplacementProcessor(context).process(model);
        }
        if (context.getRelocateClassRegex() != null) {
            new ClassRelocationProcessor(context).process(model);
        }
        if (context.getRemoveParamFqcn() != null) {
            new ParameterRemovalProcessor(context).process(model);
        }
        if (context.getFqcnToImportRegex() != null) {
            new FqcnToImportProcessor(context).process(model);
        }
        // アノテーション操作はすべてAnnotationProcessorでハンドリング
        new AnnotationProcessor(context, launcher.getFactory()).process(model);

        // 出力
        OutputManager outputManager = new OutputManager(launcher, context);
        outputManager.processAndOutput();

        System.out.println("処理が完了しました。");
    }

    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

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
                result.add(file.getAbsolutePath());
                File[] jars = file.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        result.add(jar.getAbsolutePath());
                    }
                }
            } else {
                result.add(file.getAbsolutePath());
            }
        }
        return result;
    }

    private static void printHelp() {
        System.out.println("Spoon Java Modifier");
        System.out.println("使用方法: java -jar java-modifier.jar [オプション]");
        System.out.println();
        System.out.println("基本オプション:");
        System.out.println("  -s, --source <dir>          解析対象のソースディレクトリ");
        System.out.println("  -d, --destination <dir>     出力先ディレクトリ (デフォルト: ./output)");
        System.out.println("  -cp, --classpath <path>     依存クラスパス");
        System.out.println("  -e, --encoding <enc>        文字エンコーディング (デフォルト: UTF-8)");
        System.out.println("  --newline <LF|CRLF>         出力改行コード (デフォルト: LF)");
        System.out.println();
        System.out.println("変換オプション:");
        System.out.println("  --replace-import <old:new>");
        System.out.println("  --relocate-class <regex:newPkg>");
        System.out.println("  --remove-param <fqcn>");
        System.out.println("  --fqcn-to-import <regex>");
        System.out.println("  --add-annotation-field <typeRegex:annFqcn>");
        System.out.println("  --add-annotation-type <regex:annFqcn>");
        System.out.println("  --replace-annotation <regex:annFqcn>");
        System.out.println("  --remove-annotation <regex>");
        System.out.println("  --add-annotation-by-annotation <targetRegex:annFqcn>");
    }
}
