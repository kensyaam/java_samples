package com.parser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.LinkedList;
import java.util.Collection;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtComment;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeReference;

/**
 * JavaモデルクラスをパースしてExcelファイルを生成するCLIツール。
 * 
 * <p>
 * Spring MVCなどのレガシーシステムのモデルクラスを解析し、
 * API仕様書の元ネタとなるExcelファイルを生成します。
 * </p>
 */
public class ModelParser {

    // ==========================================================================
    // 定数定義
    // ==========================================================================

    /** Excelシート名(メイン) */
    private static final String SHEET_NAME = "モデルデータ定義";

    /** Excelシート名(索引) */
    private static final String INDEX_SHEET_NAME = "Index";

    /** 必須を表すマーク */
    private static final String REQUIRED_MARK = "●";
    /** 必須でないことを表すマーク */
    private static final String NOT_REQUIRED_MARK = "";

    /** 必須を判定するアノテーション名のセット */
    private static final Set<String> REQUIRED_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "NotNull", "NonNull", "Nonnull",
            "NotEmpty", "NotBlank",
            "javax.validation.constraints.NotNull",
            "jakarta.validation.constraints.NotNull",
            "javax.validation.constraints.NotEmpty",
            "jakarta.validation.constraints.NotEmpty",
            "javax.validation.constraints.NotBlank",
            "jakarta.validation.constraints.NotBlank",
            "org.springframework.lang.NonNull",
            "lombok.NonNull"));

    /** 文字列型にマッピングするJava型 */
    private static final Set<String> STRING_TYPES = new HashSet<>(Arrays.asList(
            "String", "java.lang.String",
            "Date", "java.util.Date",
            "LocalDate", "java.time.LocalDate",
            "LocalDateTime", "java.time.LocalDateTime",
            "LocalTime", "java.time.LocalTime",
            "ZonedDateTime", "java.time.ZonedDateTime",
            "OffsetDateTime", "java.time.OffsetDateTime",
            "Instant", "java.time.Instant",
            "Timestamp", "java.sql.Timestamp",
            "UUID", "java.util.UUID",
            "char", "Character", "java.lang.Character"));

    /** 数値型にマッピングするJava型 */
    private static final Set<String> NUMBER_TYPES = new HashSet<>(Arrays.asList(
            "int", "Integer", "java.lang.Integer",
            "long", "Long", "java.lang.Long",
            "short", "Short", "java.lang.Short",
            "byte", "Byte", "java.lang.Byte",
            "float", "Float", "java.lang.Float",
            "double", "Double", "java.lang.Double",
            "BigDecimal", "java.math.BigDecimal",
            "BigInteger", "java.math.BigInteger",
            "Number", "java.lang.Number"));

    /** コレクション型にマッピングするJava型 */
    private static final Set<String> COLLECTION_TYPES = new HashSet<>(Arrays.asList(
            "List", "java.util.List",
            "ArrayList", "java.util.ArrayList",
            "LinkedList", "java.util.LinkedList",
            "Set", "java.util.Set",
            "HashSet", "java.util.HashSet",
            "TreeSet", "java.util.TreeSet",
            "Collection", "java.util.Collection",
            "Iterable", "java.lang.Iterable"));

    private static CliOptions globalOptions;

    /**
     * クラスメタデータを保持するレコード。
     */
    private static class ClassMetadata {
        final String className;
        final String qualifiedName;
        final String javadocSummary;
        final List<FieldMetadata> fields;

        ClassMetadata(String className, String qualifiedName, String javadocSummary, List<FieldMetadata> fields) {
            this.className = className;
            this.qualifiedName = qualifiedName;
            this.javadocSummary = javadocSummary;
            this.fields = fields;
        }
    }

    /**
     * フィールドメタデータを保持するレコード。
     */
    private static class FieldMetadata {
        final String jsonKey;
        final String logicalName;
        final String description;
        final String jsonType;
        final String innerRef;
        final String required;
        final String sampleValue;
        final String javaFieldName;
        final String javaType;
        final String sourceClass; // フィールドの定義元クラス（親クラスの場合は親クラス名）

        FieldMetadata(String jsonKey, String logicalName, String description,
                String jsonType, String innerRef, String required,
                String sampleValue, String javaFieldName, String javaType,
                String sourceClass) {
            this.jsonKey = jsonKey;
            this.logicalName = logicalName;
            this.description = description;
            this.jsonType = jsonType;
            this.innerRef = innerRef;
            this.required = required;
            this.sampleValue = sampleValue;
            this.javaFieldName = javaFieldName;
            this.javaType = javaType;
            this.sourceClass = sourceClass;
        }
    }

    /**
     * コマンドライン引数を保持するクラス。
     */
    private static class CliOptions {
        List<String> sourceDirs = new ArrayList<>();
        List<String> classpathEntries = new ArrayList<>();
        int complianceLevel = 21;
        String encoding = "UTF-8";
        String targetClassesFile = null;
        String outputFile = "output.xlsx";
        boolean verbose = false;
        boolean realJson = false;
    }

    // ==========================================================================
    // メインエントリポイント
    // ==========================================================================

    public static void main(String[] args) {
        // オプション定義を作成
        Options options = createOptions();

        try {
            // コマンドライン引数をパース
            CommandLineParser parser = new DefaultParser();
            CommandLine cmd = parser.parse(options, args);

            // ヘルプオプションの処理
            if (cmd.hasOption("h")) {
                printHelp(options);
                System.exit(0);
            }

            // CliOptionsに値を設定
            CliOptions cliOptions = parseCommandLine(cmd);

            // 必須オプションの検証
            validateOptions(cliOptions, options);

            // 実行
            ModelParser modelParser = new ModelParser();
            modelParser.execute(cliOptions);

        } catch (ParseException e) {
            System.err.println("Error: " + e.getMessage());
            printHelp(options);
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * コマンドラインオプションを定義する。
     */
    private static Options createOptions() {
        Options options = new Options();

        options.addOption(Option.builder("s")
                .longOpt("source")
                .hasArg()
                .argName("dirs")
                .desc("ソースディレクトリ（必須、カンマ区切りで複数指定可）")
                .build());

        options.addOption(Option.builder("cp")
                .longOpt("classpath")
                .hasArg()
                .argName("paths")
                .desc("クラスパスエントリ（カンマ区切りで複数指定可）")
                .build());

        options.addOption(Option.builder("cl")
                .longOpt("compliance")
                .hasArg()
                .argName("level")
                .desc("Javaコンプライアンスレベル（デフォルト: 21）")
                .type(Number.class)
                .build());

        options.addOption(Option.builder("e")
                .longOpt("encoding")
                .hasArg()
                .argName("encoding")
                .desc("ソースコードのエンコーディング（デフォルト: UTF-8）")
                .build());

        options.addOption(Option.builder("t")
                .longOpt("target")
                .hasArg()
                .argName("file")
                .desc("対象クラス一覧ファイル（必須、1行1クラス）")
                .build());

        options.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg()
                .argName("file")
                .desc("出力Excelファイル名（デフォルト: output.xlsx）")
                .build());

        options.addOption(Option.builder("v")
                .longOpt("verbose")
                .desc("内部モデルの構造を展開して出力する")
                .build());

        options.addOption(Option.builder("rj")
                .longOpt("real-json")
                .desc("実際のクラスをロードしてJSONを生成する")
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("ヘルプメッセージを表示")
                .build());

        return options;
    }

    /**
     * コマンドライン引数をCliOptionsに変換する。
     */
    private static CliOptions parseCommandLine(CommandLine cmd) {
        CliOptions options = new CliOptions();

        // ソースディレクトリ
        if (cmd.hasOption("s")) {
            String[] dirs = cmd.getOptionValue("s").split(",");
            for (String dir : dirs) {
                options.sourceDirs.add(dir.trim());
            }
        }

        // クラスパス
        if (cmd.hasOption("cp")) {
            String[] paths = cmd.getOptionValue("cp").split(",");
            for (String path : paths) {
                options.classpathEntries.add(path.trim());
            }
        }

        // コンプライアンスレベル
        if (cmd.hasOption("cl")) {
            options.complianceLevel = Integer.parseInt(cmd.getOptionValue("cl"));
        }

        // エンコーディング
        if (cmd.hasOption("e")) {
            options.encoding = cmd.getOptionValue("e");
        }

        // 対象クラスファイル
        if (cmd.hasOption("t")) {
            options.targetClassesFile = cmd.getOptionValue("t");
        }

        // 出力ファイル
        if (cmd.hasOption("o")) {
            options.outputFile = cmd.getOptionValue("o");
        }

        // Verboseオプション
        if (cmd.hasOption("v")) {
            options.verbose = true;
        }

        // RealJSONオプション
        if (cmd.hasOption("rj")) {
            options.realJson = true;
        }

        return options;
    }

    /**
     * 必須オプションを検証する。
     */
    private static void validateOptions(CliOptions cliOptions, Options options) {
        List<String> errors = new ArrayList<>();

        if (cliOptions.sourceDirs.isEmpty()) {
            errors.add("ソースディレクトリ (-s) は必須です。");
        }
        if (cliOptions.targetClassesFile == null) {
            errors.add("対象クラスファイル (-t) は必須です。");
        }

        if (!errors.isEmpty()) {
            for (String error : errors) {
                System.err.println("Error: " + error);
            }
            System.err.println();
            printHelp(options);
            System.exit(1);
        }
    }

    /**
     * ヘルプメッセージを表示する。
     */
    private static void printHelp(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setWidth(100);
        formatter.printHelp(
                "java -jar model-parser.jar",
                "\nJavaモデルクラスをパースしてAPI仕様書の元ネタとなるExcelファイルを生成します。\n\n",
                options,
                "\n例:\n  java -jar model-parser.jar -s src/main/java -t classes.txt -o api-spec.xlsx\n",
                true);
    }

    // ==========================================================================
    // 実行ロジック
    // ==========================================================================

    /**
     * メイン実行ロジック。
     */
    private void execute(CliOptions options) throws IOException {
        System.out.println("=== Model Parser ===");
        System.out.println("Source directories: " + options.sourceDirs);
        System.out.println("Compliance level: " + options.complianceLevel);
        System.out.println("Encoding: " + options.encoding);
        System.out.println("Target classes file: " + options.targetClassesFile);
        System.out.println("Output file: " + options.outputFile);
        System.out.println("Verbose: " + options.verbose);
        System.out.println();

        globalOptions = options;

        // 対象クラス一覧を読み込む
        Set<String> targetClasses = loadTargetClasses(options.targetClassesFile);
        System.out.println("[INFO] 対象クラス一覧を読み込みました: " + targetClasses.size() + " 件");
        for (String className : targetClasses) {
            System.out.println("  - " + className);
        }
        System.out.println();

        // Spoonの設定と実行
        System.out.println("[INFO] ソースコードを解析中...");
        CtModel model = buildSpoonModel(options);
        System.out.println("[INFO] ソースコード解析完了");
        System.out.println();

        // クラスを解析してメタデータを抽出
        System.out.println("[INFO] クラス解析を開始します");
        System.out.println("----------------------------------------");
        List<ClassMetadata> allClassMetadata = new ArrayList<>();
        Set<String> processedClasses = new HashSet<>();
        int classCount = 0;
        int totalClasses = targetClasses.size();
        int totalFields = 0;

        for (CtType<?> type : model.getAllTypes()) {
            String qualifiedName = type.getQualifiedName();
            String simpleName = type.getSimpleName();

            // 対象クラスかどうかを判定（完全修飾名または単純名で一致）
            boolean matchedByQualified = targetClasses.contains(qualifiedName);
            boolean matchedBySimple = targetClasses.contains(simpleName);

            if (matchedByQualified || matchedBySimple) {
                classCount++;
                String matchedName = matchedByQualified ? qualifiedName : simpleName;
                processedClasses.add(matchedName);

                System.out.println("[" + classCount + "/" + totalClasses + "] " + qualifiedName);
                ClassMetadata classMetadata = analyzeClass(type);
                allClassMetadata.add(classMetadata);
                totalFields += classMetadata.fields.size();
                System.out.println("         -> " + classMetadata.fields.size() + " フィールドを抽出");
            }
        }
        System.out.println("----------------------------------------");
        System.out.println();

        // 見つからなかったクラスを警告
        Set<String> notFoundClasses = new HashSet<>(targetClasses);
        notFoundClasses.removeAll(processedClasses);
        if (!notFoundClasses.isEmpty()) {
            System.out.println("[WARNING] 以下のクラスはソースディレクトリに見つかりませんでした:");
            for (String className : notFoundClasses) {
                System.out.println("  - " + className);
            }
            System.out.println();
        }

        System.out.println("[INFO] 処理完了: " + classCount + " クラス, " + totalFields + " フィールド");

        // Excelファイルを生成
        System.out.println("[INFO] Excelファイルを生成中...");
        generateExcel(allClassMetadata, options);
        System.out.println("[INFO] Excelファイル生成完了: " + options.outputFile);
    }

    /**
     * 対象クラス一覧ファイルを読み込む。
     */
    private Set<String> loadTargetClasses(String filePath) throws IOException {
        Set<String> classes = new HashSet<>();
        Path path = Paths.get(filePath);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path.toFile()), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // 空行とコメント行をスキップ
                if (!line.isEmpty() && !line.startsWith("#")) {
                    classes.add(line);
                }
            }
        }

        return classes;
    }

    /**
     * Spoonモデルを構築する。
     */
    private CtModel buildSpoonModel(CliOptions options) {
        Launcher launcher = new Launcher();

        // レガシーコード対応のため、NoClasspathモードを有効化
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(options.complianceLevel);
        launcher.getEnvironment().setSourceOutputDirectory(new File("spooned"));
        launcher.getEnvironment().setCommentEnabled(true);

        // エンコーディングを設定
        launcher.getEnvironment().setEncoding(Charset.forName(options.encoding));

        // ソースディレクトリを追加
        for (String dir : options.sourceDirs) {
            launcher.addInputResource(dir);
        }

        // クラスパスを構築
        List<String> classpathList = new ArrayList<>();
        for (String entry : options.classpathEntries) {
            File file = new File(entry);
            if (file.isDirectory()) {
                // ディレクトリ自体を追加
                classpathList.add(file.getAbsolutePath());
                // ディレクトリ直下のJARファイルを追加
                File[] jarFiles = file.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jarFiles != null) {
                    for (File jarFile : jarFiles) {
                        classpathList.add(jarFile.getAbsolutePath());
                    }
                }
            } else if (file.isFile() && file.getName().endsWith(".jar")) {
                classpathList.add(file.getAbsolutePath());
            }
        }

        if (!classpathList.isEmpty()) {
            launcher.getEnvironment().setSourceClasspath(
                    classpathList.toArray(new String[0]));
        }

        // モデルを構築
        launcher.buildModel();
        return launcher.getModel();
    }

    // ==========================================================================
    // クラス解析ロジック
    // ==========================================================================

    /**
     * クラスを解析してクラスメタデータを抽出する。
     * 親クラスも再帰的に辿ってフィールドを収集する。
     * フィールドは親クラスから順に出力される。
     */
    private ClassMetadata analyzeClass(CtType<?> type) {
        List<FieldMetadata> metadataList = new ArrayList<>();
        String className = type.getSimpleName();
        String qualifiedName = type.getQualifiedName();
        String javadocSummary = parseClassJavadoc(type);

        Set<String> visitedTypes = new HashSet<>();
        visitedTypes.add(qualifiedName);

        // 親クラスを再帰的に辿ってフィールドを収集（先に親クラスのフィールドを収集）
        collectParentClassFieldsRecursive(type, "", metadataList, visitedTypes);

        // 自クラスのフィールドを走査
        collectFieldsRecursive(type, className, "", metadataList, visitedTypes);

        return new ClassMetadata(className, qualifiedName, javadocSummary, metadataList);
    }

    /**
     * クラスからフィールドを再帰的に収集する。
     */
    private void collectFieldsRecursive(CtType<?> type, String sourceClass, String prefix,
            List<FieldMetadata> metadataList, Set<String> visitedTypes) {
        for (CtField<?> field : type.getFields()) {
            if (field.isStatic()) {
                continue;
            }

            FieldMetadata metadata = analyzeField(field, sourceClass);

            // JSONキーに接頭辞を追加
            String jsonKey = prefix + metadata.jsonKey;
            FieldMetadata updatedMetadata = new FieldMetadata(
                    jsonKey,
                    metadata.logicalName,
                    metadata.description,
                    metadata.jsonType,
                    metadata.innerRef,
                    metadata.required,
                    metadata.sampleValue,
                    metadata.javaFieldName,
                    metadata.javaType,
                    metadata.sourceClass);

            metadataList.add(updatedMetadata);

            // verboseモード時、かつ再帰可能な場合は再帰的に収集
            if (globalOptions.verbose && !metadata.innerRef.isEmpty() && !visitedTypes.contains(metadata.innerRef)) {
                CtType<?> innerType = null;
                CtTypeReference<?> typeRef = field.getType();

                // コレクションの場合は要素型を取得
                if (metadata.jsonType.equals("array")) {
                    if (typeRef.isArray()) {
                        innerType = ((CtArrayTypeReference<?>) typeRef).getComponentType().getTypeDeclaration();
                    } else if (isCollectionType(typeRef.getSimpleName())
                            || isCollectionType(typeRef.getQualifiedName())) {
                        List<CtTypeReference<?>> typeArgs = typeRef.getActualTypeArguments();
                        if (typeArgs != null && !typeArgs.isEmpty()) {
                            innerType = typeArgs.get(0).getTypeDeclaration();
                        }
                    }
                } else {
                    innerType = typeRef.getTypeDeclaration();
                }

                if (innerType != null && !isPrimitiveOrStandard(innerType)) {
                    String nextPrefix = jsonKey + (metadata.jsonType.equals("array") ? "[]." : ".");
                    String innerClassName = innerType.getSimpleName();
                    visitedTypes.add(metadata.innerRef);

                    // 親クラスのフィールドを先に収集
                    collectParentClassFieldsRecursive(innerType, nextPrefix, metadataList, visitedTypes);
                    // 自クラスのフィールドを収集
                    collectFieldsRecursive(innerType, innerClassName, nextPrefix, metadataList, visitedTypes);

                    visitedTypes.remove(metadata.innerRef);
                }
            }
        }
    }

    /**
     * 標準クラスまたはプリミティブ型かどうかを判定する。
     */
    private boolean isPrimitiveOrStandard(CtType<?> type) {
        String name = type.getSimpleName();
        String qualifiedName = type.getQualifiedName();
        return STRING_TYPES.contains(name) || STRING_TYPES.contains(qualifiedName) ||
                NUMBER_TYPES.contains(name) || NUMBER_TYPES.contains(qualifiedName) ||
                "boolean".equals(name) || "Boolean".equals(name) || "java.lang.Boolean".equals(qualifiedName) ||
                qualifiedName.startsWith("java.lang.") || qualifiedName.startsWith("java.util.");
    }

    /**
     * 親クラスを再帰的に辿ってフィールドを再帰的に収集する。
     */
    private void collectParentClassFieldsRecursive(CtType<?> type, String prefix,
            List<FieldMetadata> metadataList, Set<String> visitedTypes) {
        if (!(type instanceof CtClass)) {
            return;
        }

        CtClass<?> ctClass = (CtClass<?>) type;
        CtTypeReference<?> superClassRef = ctClass.getSuperclass();

        if (superClassRef == null) {
            return;
        }

        String superClassName = superClassRef.getQualifiedName();
        if ("java.lang.Object".equals(superClassName)) {
            return;
        }

        CtType<?> superType = superClassRef.getTypeDeclaration();
        if (superType != null) {
            String parentClassName = superType.getSimpleName();

            // さらに上位の親クラスを遡る
            collectParentClassFieldsRecursive(superType, prefix, metadataList, visitedTypes);

            System.out.println("         -> 親クラス " + parentClassName + " のフィールドを収集中...");

            // 親クラス自体のフィールドを収集（再帰対応）
            collectFieldsRecursive(superType, parentClassName, prefix, metadataList, visitedTypes);
        }
    }

    /**
     * クラスのJavadocから1行目を抽出する。
     */
    private String parseClassJavadoc(CtType<?> type) {
        List<CtComment> comments = type.getComments();
        for (CtComment comment : comments) {
            if (comment.getCommentType() == CtComment.CommentType.JAVADOC) {
                String content = comment.getContent();
                content = cleanJavadocContent(content);
                // 1行目のみ取得
                String[] lines = content.split("\n", 2);
                return lines[0].trim();
            }
        }
        return "";
    }

    /**
     * フィールドを解析してメタデータを抽出する。
     * 
     * @param field       解析対象のフィールド
     * @param sourceClass フィールドの定義元クラス名
     */
    private FieldMetadata analyzeField(CtField<?> field, String sourceClass) {
        String fieldName = field.getSimpleName();
        CtTypeReference<?> fieldType = field.getType();

        // アノテーションからJSONキーを取得（なければフィールド名を使用）
        String jsonKey = extractJsonKey(field, fieldName);

        // Javadoc解析
        String[] javadocParts = parseFieldJavadoc(field);
        String logicalName = javadocParts[0];
        String description = javadocParts[1];

        // 型変換
        String[] typeInfo = convertType(fieldType);
        String jsonType = typeInfo[0];
        String innerRef = typeInfo[1];

        // 必須判定
        String required = checkRequired(field) ? REQUIRED_MARK : NOT_REQUIRED_MARK;

        // サンプル値生成
        String sampleValue = generateSampleValue(jsonType, innerRef);

        // Java型情報（標準クラスのパッケージ名を除外）
        String javaType = fieldType != null ? simplifyJavaType(fieldType.toString()) : "unknown";

        return new FieldMetadata(
                jsonKey, // JSONキー（アノテーションまたはフィールド名）
                logicalName, // 論理名
                description, // 説明
                jsonType, // JSON型
                innerRef, // 内部要素/参照
                required, // 必須
                sampleValue, // サンプル値
                fieldName, // Javaフィールド名
                javaType, // Java型
                sourceClass // 定義元クラス
        );
    }

    /**
     * フィールドのアノテーションからJSONキー（パラメータ名）を抽出する。
     * 
     * <p>
     * 対応アノテーション:
     * <ul>
     * <li>@JsonProperty("name") - Jackson</li>
     * <li>@SerializedName("name") - Gson</li>
     * <li>@XmlElement(name="name") - JAXB</li>
     * </ul>
     */
    private String extractJsonKey(CtField<?> field, String defaultName) {
        for (CtAnnotation<?> annotation : field.getAnnotations()) {
            String annotationName = annotation.getAnnotationType().getSimpleName();

            switch (annotationName) {
                case "JsonProperty":
                    // @JsonProperty("name") または @JsonProperty(value = "name")
                    String jsonPropertyValue = getAnnotationValue(annotation, "value");
                    if (jsonPropertyValue != null && !jsonPropertyValue.isEmpty()) {
                        return jsonPropertyValue;
                    }
                    break;

                case "SerializedName":
                    // @SerializedName("name") または @SerializedName(value = "name")
                    String serializedNameValue = getAnnotationValue(annotation, "value");
                    if (serializedNameValue != null && !serializedNameValue.isEmpty()) {
                        return serializedNameValue;
                    }
                    break;

                case "XmlElement":
                    // @XmlElement(name = "name")
                    String xmlElementName = getAnnotationValue(annotation, "name");
                    if (xmlElementName != null && !xmlElementName.isEmpty()
                            && !xmlElementName.equals("##default")) {
                        return xmlElementName;
                    }
                    break;
            }
        }

        return defaultName;
    }

    /**
     * アノテーションから指定されたキーの値を取得する。
     */
    private String getAnnotationValue(CtAnnotation<?> annotation, String key) {
        try {
            Object value = annotation.getValue(key);
            if (value != null) {
                String strValue = value.toString();
                // 文字列リテラルの引用符を除去
                if (strValue.startsWith("\"") && strValue.endsWith("\"")) {
                    return strValue.substring(1, strValue.length() - 1);
                }
                return strValue;
            }
        } catch (Exception e) {
            // 値が取得できない場合は無視
        }
        return null;
    }

    /**
     * Java型文字列からJava標準クラスのパッケージ名を除外する。
     */
    private String simplifyJavaType(String javaType) {
        // java.lang, java.util, java.math, java.time, java.sql のパッケージ名を除外
        String simplified = javaType
                .replaceAll("java\\.lang\\.", "")
                .replaceAll("java\\.util\\.", "")
                .replaceAll("java\\.math\\.", "")
                .replaceAll("java\\.time\\.", "")
                .replaceAll("java\\.sql\\.", "");
        return simplified;
    }

    /**
     * フィールドのJavadocを解析して論理名と説明を抽出する。
     * 
     * @return [論理名, 説明] の配列
     */
    private String[] parseFieldJavadoc(CtField<?> field) {
        String logicalName = "";
        String description = "";

        List<CtComment> comments = field.getComments();
        for (CtComment comment : comments) {
            if (comment.getCommentType() == CtComment.CommentType.JAVADOC) {
                String content = comment.getContent();

                // Javadocタグを除去してテキストを抽出
                content = cleanJavadocContent(content);

                // 1行目と2行目以降に分割
                String[] lines = content.split("\n", 2);
                logicalName = lines[0].trim();
                if (lines.length > 1) {
                    description = lines[1].trim();
                }
                break;
            }
        }

        return new String[] { logicalName, description };
    }

    /**
     * Javadocコンテンツをクリーンアップする。
     */
    private String cleanJavadocContent(String content) {
        // 各行の先頭の * を除去
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("*")) {
                line = line.substring(1).trim();
            }
            // @タグで始まる行は除去
            if (!line.startsWith("@")) {
                if (sb.length() > 0 && !line.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(line);
            }
        }
        return sb.toString().trim();
    }

    /**
     * Java型をJSON型に変換する。
     * 
     * @return [jsonType, innerRef] の配列
     */
    private String[] convertType(CtTypeReference<?> typeRef) {
        if (typeRef == null) {
            return new String[] { "object", "" };
        }

        String typeName = typeRef.getSimpleName();
        String qualifiedName = typeRef.getQualifiedName();

        // 配列型の場合
        if (typeRef.isArray() && typeRef instanceof CtArrayTypeReference<?>) {
            CtArrayTypeReference<?> arrayRef = (CtArrayTypeReference<?>) typeRef;
            CtTypeReference<?> componentType = arrayRef.getComponentType();
            String innerType = componentType != null ? componentType.getQualifiedName() : "unknown";
            return new String[] { "array", innerType };
        }

        // コレクション型の場合
        if (isCollectionType(typeName) || isCollectionType(qualifiedName)) {
            List<CtTypeReference<?>> typeArgs = typeRef.getActualTypeArguments();
            String innerType = "";
            if (typeArgs != null && !typeArgs.isEmpty()) {
                innerType = typeArgs.get(0).getQualifiedName();
            }
            return new String[] { "array", innerType };
        }

        // 文字列型の場合
        if (STRING_TYPES.contains(typeName) || STRING_TYPES.contains(qualifiedName)) {
            return new String[] { "string", "" };
        }

        // 数値型の場合
        if (NUMBER_TYPES.contains(typeName) || NUMBER_TYPES.contains(qualifiedName)) {
            return new String[] { "number", "" };
        }

        // Boolean型の場合
        if ("boolean".equals(typeName) || "Boolean".equals(typeName) ||
                "java.lang.Boolean".equals(qualifiedName)) {
            return new String[] { "boolean", "" };
        }

        // Map型の場合
        if (typeName.equals("Map") || qualifiedName.startsWith("java.util.Map") ||
                qualifiedName.equals("java.util.HashMap") ||
                qualifiedName.equals("java.util.LinkedHashMap") ||
                qualifiedName.equals("java.util.TreeMap")) {
            return new String[] { "object", "Map" };
        }

        // その他のクラスはobjectとして扱う
        return new String[] { "object", qualifiedName };
    }

    /**
     * コレクション型かどうかを判定する。
     */
    private boolean isCollectionType(String typeName) {
        return COLLECTION_TYPES.contains(typeName);
    }

    /**
     * 必須フィールドかどうかを判定する。
     */
    private boolean checkRequired(CtField<?> field) {
        for (CtAnnotation<?> annotation : field.getAnnotations()) {
            String annotationName = annotation.getAnnotationType().getSimpleName();
            String qualifiedName = annotation.getAnnotationType().getQualifiedName();
            if (REQUIRED_ANNOTATIONS.contains(annotationName) ||
                    REQUIRED_ANNOTATIONS.contains(qualifiedName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * JSON型に応じたサンプル値を生成する。
     */
    private String generateSampleValue(String jsonType, String innerRef) {
        switch (jsonType) {
            case "string":
                return "\"text\"";
            case "number":
                return "123";
            case "boolean":
                return "true";
            case "array":
                if (!innerRef.isEmpty()) {
                    // 内部要素の型に応じてサンプル値を調整
                    if (STRING_TYPES.contains(innerRef)) {
                        return "[\"item1\", \"item2\"]";
                    } else if (NUMBER_TYPES.contains(innerRef)) {
                        return "[1, 2, 3]";
                    } else {
                        return "[{...}]";
                    }
                }
                return "[]";
            case "object":
                return "{...}";
            default:
                return "";
        }
    }

    // ==========================================================================
    // Excel出力ロジック
    // ==========================================================================

    /**
     * Excelファイルを生成する。
     */
    private void generateExcel(List<ClassMetadata> classMetadataList, CliOptions options) throws IOException {
        String outputFile = options.outputFile;
        try (Workbook workbook = new XSSFWorkbook()) {
            // スタイルを作成
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle javaHeaderStyle = createJavaHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle classTitleStyle = createClassTitleStyle(workbook);
            CellStyle linkStyle = createLinkStyle(workbook);
            CellStyle jsonStyle = createJsonStyle(workbook);

            // 1パス目: クラス名から名前付き範囲名のマップを作成
            Map<String, String> classToNamedRangeMap = new HashMap<>();
            // ルックアップ用のメタデータマップ作成
            Map<String, ClassMetadata> classMetadataMap = new HashMap<>();
            for (ClassMetadata classMeta : classMetadataList) {
                String namedRangeName = "Class_" + classMeta.qualifiedName.replaceAll("[^a-zA-Z0-9_]", "_");
                classToNamedRangeMap.put(classMeta.qualifiedName, namedRangeName);
                if (!classToNamedRangeMap.containsKey(classMeta.className)) {
                    classToNamedRangeMap.put(classMeta.className, namedRangeName);
                }
                classMetadataMap.put(classMeta.qualifiedName, classMeta);
            }

            // Index（索引）シートを作成
            Sheet indexSheet = workbook.createSheet(INDEX_SHEET_NAME);
            String[] indexHeaders = { "パッケージ", "クラス名", "概要" };
            Row indexHeaderRow = indexSheet.createRow(0);
            for (int i = 0; i < indexHeaders.length; i++) {
                Cell cell = indexHeaderRow.createCell(i);
                cell.setCellValue(indexHeaders[i]);
                cell.setCellStyle(headerStyle);
            }

            int indexRowNum = 1;
            for (ClassMetadata classMeta : classMetadataList) {
                Row row = indexSheet.createRow(indexRowNum++);
                String packageName = "";
                if (classMeta.qualifiedName.contains(".")) {
                    packageName = classMeta.qualifiedName.substring(0, classMeta.qualifiedName.lastIndexOf("."));
                }
                createCell(row, 0, packageName, dataStyle);

                // クラス名セル（リンク付き）
                Cell classNameCell = row.createCell(1);
                classNameCell.setCellValue(classMeta.className);
                String namedRangeName = classToNamedRangeMap.get(classMeta.qualifiedName);
                if (namedRangeName != null) {
                    Hyperlink link = workbook.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
                    link.setAddress("'" + SHEET_NAME + "'!" + namedRangeName);
                    classNameCell.setHyperlink(link);
                    classNameCell.setCellStyle(linkStyle);
                } else {
                    classNameCell.setCellStyle(dataStyle);
                }

                createCell(row, 2, classMeta.javadocSummary, dataStyle);
            }

            // Indexシートの列幅調整
            for (int i = 0; i < indexHeaders.length; i++) {
                indexSheet.autoSizeColumn(i);
                if (indexSheet.getColumnWidth(i) < 4000) {
                    indexSheet.setColumnWidth(i, 4000);
                }
            }

            // メインデータシートを作成
            Sheet sheet = workbook.createSheet(SHEET_NAME);

            // ヘッダー定義
            String[] headers = {
                    "JSONキー", "論理名", "説明", "JSON型", "内部要素/参照",
                    "必須", "サンプル値", "Javaフィールド名", "Java型", "定義元クラス"
            };

            int rowNum = 0;

            // 2パス目: クラスごとにテーブルを出力
            for (ClassMetadata classMeta : classMetadataList) {
                // パッケージ名行を作成
                Row packageRow = sheet.createRow(rowNum++);
                String packageName = "";
                if (classMeta.qualifiedName.contains(".")) {
                    packageName = classMeta.qualifiedName.substring(0, classMeta.qualifiedName.lastIndexOf("."));
                }
                Cell packageCell = packageRow.createCell(0);
                packageCell.setCellValue("Package: " + packageName);
                // パッケージ行もクラス見出しスタイルを流用（必要に応じて別途定義も可能）
                packageCell.setCellStyle(classTitleStyle);

                // クラス名見出し行を作成
                Row classTitleRow = sheet.createRow(rowNum);
                String classTitle = classMeta.className; // シンプルネームを使用
                String classSummary = "";
                if (classMeta.javadocSummary != null && !classMeta.javadocSummary.isEmpty()) {
                    classSummary = classMeta.javadocSummary;
                }
                Cell titleCell = classTitleRow.createCell(0);
                titleCell.setCellValue(classTitle);
                titleCell.setCellStyle(classTitleStyle);
                Cell titleSummaryCell = classTitleRow.createCell(1);
                titleSummaryCell.setCellValue(classSummary);
                titleSummaryCell.setCellStyle(classTitleStyle);

                // 名前付き範囲を作成（1パス目で決めた名前を使用）
                String namedRangeName = classToNamedRangeMap.get(classMeta.qualifiedName);
                try {
                    org.apache.poi.ss.usermodel.Name namedRange = workbook.createName();
                    namedRange.setNameName(namedRangeName);
                    // クラス名見出し行をリンク先とする
                    String cellRef = "'" + SHEET_NAME + "'!$A$" + (rowNum + 1);
                    namedRange.setRefersToFormula(cellRef);
                } catch (Exception e) {
                    System.out.println("         -> [WARNING] 名前付き範囲 " + namedRangeName + " の作成をスキップしました");
                }

                rowNum++;

                // ヘッダー行を作成
                Row headerRow = sheet.createRow(rowNum++);
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    // Java関連の列（インデックス7, 8, 9）には別のスタイルを適用
                    if (i >= 7) {
                        cell.setCellStyle(javaHeaderStyle);
                    } else {
                        cell.setCellStyle(headerStyle);
                    }
                }

                // データ行を作成
                for (FieldMetadata metadata : classMeta.fields) {
                    Row row = sheet.createRow(rowNum++);

                    createCell(row, 0, metadata.jsonKey, dataStyle);
                    createCell(row, 1, metadata.logicalName, dataStyle);
                    createCell(row, 2, metadata.description, dataStyle);
                    createCell(row, 3, metadata.jsonType, dataStyle);

                    // 内部要素/参照セル - 他クラスへのリンクを設定
                    Cell innerRefCell = row.createCell(4);
                    String displayRef = metadata.innerRef;
                    if (displayRef != null && displayRef.contains(".")) {
                        displayRef = displayRef.substring(displayRef.lastIndexOf(".") + 1);
                    }
                    innerRefCell.setCellValue(displayRef != null ? displayRef : "");
                    if (metadata.innerRef != null && !metadata.innerRef.isEmpty()
                            && classToNamedRangeMap.containsKey(metadata.innerRef)) {
                        // 名前付き範囲へのリンクを作成
                        String targetName = classToNamedRangeMap.get(metadata.innerRef);
                        Hyperlink link = workbook.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);
                        link.setAddress(targetName);
                        innerRefCell.setHyperlink(link);
                        innerRefCell.setCellStyle(linkStyle);
                    } else {
                        innerRefCell.setCellStyle(dataStyle);
                    }

                    createCell(row, 5, metadata.required, dataStyle);
                    createCell(row, 6, metadata.sampleValue, dataStyle);
                    createCell(row, 7, metadata.javaFieldName, dataStyle);
                    createCell(row, 8, metadata.javaType, dataStyle);
                    // 定義元クラス
                    createCell(row, 9, metadata.sourceClass, dataStyle);
                }

                // JSONイメージを出力
                rowNum = appendJsonImage(sheet, workbook, rowNum, classMeta, jsonStyle, options, classMetadataMap);

                // クラス間に空行を追加
                rowNum++;
            }

            // 列幅を自動調整
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
                // 最小幅を設定
                int currentWidth = sheet.getColumnWidth(i);
                if (currentWidth < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            // ファイルに書き出し
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                workbook.write(fos);
            }
        }
    }

    /**
     * クラス名見出し用のスタイルを作成する。
     */
    private CellStyle createClassTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        // 背景色（薄いグレー）
        // style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        // style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // フォント（太字・メイリオ）
        Font font = workbook.createFont();
        font.setFontName("Meiryo");
        font.setBold(true);
        font.setItalic(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);

        return style;
    }

    /**
     * 各モデルのJSONイメージを出力する。
     */
    private int appendJsonImage(Sheet sheet, Workbook workbook, int rowNum, ClassMetadata classMeta,
            CellStyle jsonStyle, CliOptions options, Map<String, ClassMetadata> classMetadataMap) {
        try {
            // JSON生成
            String json;
            if (options.realJson) {
                json = generateRealJsonImage(classMeta, options, classMetadataMap);
            } else {
                json = generateJsonImage(classMeta.fields);
            }

            // 10行分を結合
            int startRowNum = rowNum;
            int endRowNum = rowNum + 9;

            for (int i = startRowNum; i <= endRowNum; i++) {
                Row row = sheet.createRow(i);
                // 結合セルの各セルにもスタイルを適用（境界線のため）
                for (int j = 0; j <= 9; j++) {
                    Cell cell = row.createCell(j);
                    cell.setCellStyle(jsonStyle);
                    // 最初のセルの左上にのみ値を設定
                    if (i == startRowNum && j == 0) {
                        cell.setCellValue(json);
                    }
                }
                // 各行の高さを設定（15ポイント * 10行 = 150ポイント程度の領域を確保）
                row.setHeightInPoints(15f);
            }

            // セル結合（A～J列、縦10行）
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(
                    startRowNum, endRowNum, 0, 9));

            return endRowNum + 1;
        } catch (Exception e) {
            System.err
                    .println("         -> [ERROR] JSON出力中にエラーが発生しました (" + classMeta.className + "): " + e.getMessage());
            return rowNum;
        }
    }

    /**
     * フィールドメタデータからJSONイメージを生成する。
     */
    private String generateJsonImage(List<FieldMetadata> fields) throws Exception {
        Map<String, Object> root = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        for (FieldMetadata field : fields) {
            String jsonKey = field.jsonKey;
            Object sampleValue = parseSampleValue(field.sampleValue, field.jsonType);

            // ドット記法を解釈してMapを構築
            putValueByPath(root, jsonKey, sampleValue);
        }

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    /**
     * ドット記法（a.bやa[].b）のパスに従って値をMapにセットする。
     */
    @SuppressWarnings("unchecked")
    private void putValueByPath(Map<String, Object> map, String path, Object value) {
        String[] parts = path.split("\\.", 2);
        String currentPart = parts[0];

        boolean isArray = currentPart.endsWith("[]");
        String key = isArray ? currentPart.substring(0, currentPart.length() - 2) : currentPart;

        if (parts.length == 1) {
            // 末端の場合
            if (isArray) {
                List<Object> list = (List<Object>) map.computeIfAbsent(key, k -> new ArrayList<>());
                list.add(value);
            } else {
                map.put(key, value);
            }
        } else {
            // 途中の場合
            if (isArray) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) map.computeIfAbsent(key,
                        k -> new ArrayList<>());
                Map<String, Object> childMap;
                if (list.isEmpty()) {
                    childMap = new HashMap<>();
                    list.add(childMap);
                } else {
                    childMap = list.get(0);
                }
                putValueByPath(childMap, parts[1], value);
            } else {
                Map<String, Object> childMap = (Map<String, Object>) map.computeIfAbsent(key, k -> new HashMap<>());
                putValueByPath(childMap, parts[1], value);
            }
        }
    }

    /**
     * サンプル値文字列を適切なJavaオブジェクトに変換する。
     */
    private Object parseSampleValue(String sampleValue, String jsonType) {
        if (sampleValue == null || sampleValue.isEmpty()) {
            return null;
        }

        // 引用符の除去（文字列の場合など）
        String cleanValue = sampleValue;
        if (cleanValue.startsWith("\"") && cleanValue.endsWith("\"")) {
            cleanValue = cleanValue.substring(1, cleanValue.length() - 1);
        }

        switch (jsonType) {
            case "number":
                try {
                    if (cleanValue.contains(".")) {
                        return Double.parseDouble(cleanValue);
                    } else {
                        return Long.parseLong(cleanValue);
                    }
                } catch (NumberFormatException e) {
                    return 0;
                }
            case "boolean":
                return Boolean.parseBoolean(cleanValue);
            case "string":
                return cleanValue;
            case "array":
                return new ArrayList<>();
            case "object":
                return new HashMap<>();
            default:
                return cleanValue;
        }
    }

    /**
     * JSONイメージ出力用のスタイルを作成する。
     */
    private CellStyle createJsonStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        // 境界線（点線または実線）
        style.setBorderTop(BorderStyle.DASHED);
        style.setBorderBottom(BorderStyle.DASHED);
        style.setBorderLeft(BorderStyle.DASHED);
        style.setBorderRight(BorderStyle.DASHED);

        // 背景色（ごく薄いグレー）
        style.setFillForegroundColor(IndexedColors.AQUA.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // フォント（等幅フォント、サイズ小さめ）
        Font font = workbook.createFont();
        font.setFontName("Consolas");
        font.setFontHeightInPoints((short) 9);
        style.setFont(font);

        // 配置（左詰め、上詰め、折り返し）
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        style.setWrapText(true);

        return style;
    }

    /**
     * 実際のクラスをロードしてインスタンス化し、JacksonでJSONイメージを生成する。
     */
    private String generateRealJsonImage(ClassMetadata classMeta, CliOptions options,
            Map<String, ClassMetadata> classMetadataMap) throws Exception {
        String qualifiedName = classMeta.qualifiedName;
        List<URL> urls = new ArrayList<>();
        for (String cp : options.classpathEntries) {
            File file = new File(cp);
            if (file.isDirectory()) {
                // ディレクトリ自体を追加
                urls.add(file.toURI().toURL());
                // ディレクトリ直下のJARファイルを追加
                File[] jarFiles = file.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jarFiles != null) {
                    for (File jarFile : jarFiles) {
                        urls.add(jarFile.toURI().toURL());
                    }
                }
            } else if (file.isFile() && file.getName().endsWith(".jar")) {
                urls.add(file.toURI().toURL());
            }
        }
        for (String src : options.sourceDirs) {
            // ソースディレクトリも（コンパイル済みクラスがあるかもしれないので）念のため追加
            urls.add(new File(src).toURI().toURL());
        }

        // System.out.println("--- Classpath for Real Class Loading ---");
        // for (URL url : urls) {
        // System.out.println(url);
        // }
        // System.out.println("----------------------------------------");

        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), this.getClass().getClassLoader())) {
            Class<?> clazz = Class.forName(qualifiedName, true, loader);
            Object instance = clazz.getDeclaredConstructor().newInstance();

            // メタデータを使ってフィールドに値をセット
            populateObject(instance, classMeta, classMetadataMap, new HashSet<>(), loader);

            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            // フィールドのみを対象とし、ゲッター（isNewなど）は無視する
            mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            mapper.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE);
            mapper.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(instance);
        } catch (ClassNotFoundException e) {
            return "// [WARNING] クラスが見つかりませんでした: " + qualifiedName + "\n" +
                    "// 定義データから生成したJSONを以下に出力します:\n" +
                    generateJsonImage(classMeta.fields);
        } catch (NoSuchMethodException e) {
            return "// [WARNING] デフォルトコンストラクタが見つかりませんでした: " + qualifiedName + "\n" +
                    "// 定義データから生成したJSONを以下に出力します:\n" +
                    generateJsonImage(classMeta.fields);
        } catch (Exception e) {
            return "// [ERROR] JSON生成中にエラーが発生しました: " + e.getMessage() + "\n" +
                    "// 定義データから生成したJSONを以下に出力します:\n" +
                    generateJsonImage(classMeta.fields);
        }
    }

    /**
     * リンク用のスタイルを作成する。
     */
    private CellStyle createLinkStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        // フォント（青色・下線）
        Font font = workbook.createFont();
        font.setFontName("Meiryo");
        font.setColor(IndexedColors.BLUE.getIndex());
        font.setUnderline(Font.U_SINGLE);
        style.setFont(font);

        // 境界線
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * セルを作成する。
     */
    private void createCell(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value != null ? value : "");
        cell.setCellStyle(style);
    }

    /**
     * クラス名見出し用のスタイルを作成する。
     */

    /**
     * ヘッダー用のスタイルを作成する。
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        // 背景色
        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // フォント
        Font font = workbook.createFont();
        font.setFontName("Meiryo");
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);

        // 配置
        style.setAlignment(HorizontalAlignment.CENTER);

        // 境界線
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * Java実装に関連するヘッダー用のスタイルを作成する。
     */
    private CellStyle createJavaHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        // 背景色（落ち着いた緑系）
        style.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // フォント
        Font font = workbook.createFont();
        font.setFontName("Meiryo");
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);

        // 配置
        style.setAlignment(HorizontalAlignment.CENTER);

        // 境界線
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * データ用のスタイルを作成する。
     */
    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        // フォント
        Font font = workbook.createFont();
        font.setFontName("Meiryo");
        style.setFont(font);

        // 境界線
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * インスタンスのフィールドに値を再帰的にセットする。
     */
    private void populateObject(Object instance, ClassMetadata classMeta, Map<String, ClassMetadata> classMetadataMap,
            Set<String> visited, ClassLoader loader) {
        if (instance == null || classMeta == null) {
            return;
        }

        // 循環参照防止（現在のパスに含まれているかチェック）
        String key = classMeta.qualifiedName;
        if (visited.contains(key)) {
            return;
        }
        visited.add(key);

        try {
            for (FieldMetadata fieldMeta : classMeta.fields) {
                try {
                    Field field = getFieldRecursive(instance.getClass(), fieldMeta.javaFieldName);
                    if (field == null) {
                        continue;
                    }
                    field.setAccessible(true);

                    // staticやfinalフィールドはスキップ
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                        continue;
                    }

                    Object value = null;

                    // 配列/コレクションの場合
                    // 配列/コレクションの場合
                    if ("array".equals(fieldMeta.jsonType)) {
                        if (Collection.class.isAssignableFrom(field.getType())) {
                            Collection<Object> collection = createCollectionInstance(field.getType());

                            if (collection != null) {
                                if (fieldMeta.innerRef != null && !fieldMeta.innerRef.isEmpty()) {
                                    // 内部要素がクラス定義にあるか確認
                                    ClassMetadata nestedMeta = classMetadataMap.get(fieldMeta.innerRef);
                                    if (nestedMeta != null) {
                                        // オブジェクトのリスト
                                        try {
                                            Class<?> nestedClass = Class.forName(nestedMeta.qualifiedName, true,
                                                    loader);
                                            Object nestedInstance = nestedClass.getDeclaredConstructor().newInstance();
                                            populateObject(nestedInstance, nestedMeta, classMetadataMap, visited,
                                                    loader);
                                            collection.add(nestedInstance);
                                        } catch (Exception e) {
                                            System.out.println("Failed to instantiate nested object for Collection: "
                                                    + fieldMeta.javaFieldName + " - " + e.getMessage());
                                            e.printStackTrace();
                                        }
                                    } else {
                                        // プリミティブやStringのリスト（簡易対応）
                                        if ("String".equals(fieldMeta.innerRef)
                                                || "string".equals(fieldMeta.innerRef)) {
                                            collection.add("text");
                                        } else if ("Integer".equals(fieldMeta.innerRef)
                                                || "int".equals(fieldMeta.innerRef)) {
                                            collection.add(123);
                                        }
                                    }
                                }
                                value = collection;
                            }
                        }
                    }
                    // オブジェクト（ネスト）の場合
                    else if ("object".equals(fieldMeta.jsonType) && fieldMeta.innerRef != null
                            && !fieldMeta.innerRef.isEmpty() && !"Map".equals(fieldMeta.innerRef)) {
                        ClassMetadata nestedMeta = classMetadataMap.get(fieldMeta.innerRef);
                        if (nestedMeta != null) {
                            try {
                                Class<?> nestedClass = Class.forName(nestedMeta.qualifiedName, true, loader);
                                Object nestedInstance = nestedClass.getDeclaredConstructor().newInstance();
                                populateObject(nestedInstance, nestedMeta, classMetadataMap, visited, loader);
                                value = nestedInstance;
                            } catch (Exception e) {
                                System.out.println("Failed to instantiate nested object: " + fieldMeta.javaFieldName
                                        + " - " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
                    // その他の場合 (string, number, boolean)
                    else {
                        value = parseSampleValue(fieldMeta.sampleValue, fieldMeta.jsonType);
                        value = convertToFieldType(value, field.getType());
                    }

                    if (value != null) {
                        field.set(instance, value);
                    }

                } catch (Exception e) {
                    System.out.println("Set field error: " + fieldMeta.javaFieldName + " - " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } finally {
            // Backtracking: この枝を抜けたらvisitedから削除
            visited.remove(key);
        }
    }

    /**
     * クラス階層を遡ってフィールドを取得する。
     */
    private Field getFieldRecursive(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 値をフィールドの型に合わせて変換する。
     */
    private Object convertToFieldType(Object value, Class<?> type) {
        if (value == null)
            return null;
        if (type.isAssignableFrom(value.getClass())) {
            return value;
        }

        // Number conversions
        if (value instanceof Number) {
            Number num = (Number) value;
            if (type == int.class || type == Integer.class)
                return num.intValue();
            if (type == long.class || type == Long.class)
                return num.longValue();
            if (type == double.class || type == Double.class)
                return num.doubleValue();
            if (type == float.class || type == Float.class)
                return num.floatValue();
            if (type == short.class || type == Short.class)
                return num.shortValue();
            if (type == byte.class || type == Byte.class)
                return num.byteValue();
            if (type == java.math.BigDecimal.class)
                return new java.math.BigDecimal(num.toString());
        }

        // Boolean conversions
        if (value instanceof Boolean) {
            if (type == boolean.class || type == Boolean.class)
                return value;
        }

        // String to temporal types
        if (value instanceof String) {
            String str = (String) value;
            try {
                if (type == java.time.LocalDate.class) {
                    return java.time.LocalDate.now();
                } else if (type == java.time.LocalDateTime.class) {
                    return java.time.LocalDateTime.now();
                } else if (type == java.time.LocalTime.class) {
                    return java.time.LocalTime.now();
                } else if (type == java.time.ZonedDateTime.class) {
                    return java.time.ZonedDateTime.now();
                } else if (type == java.time.OffsetDateTime.class) {
                    return java.time.OffsetDateTime.now();
                } else if (type == java.time.Instant.class) {
                    return java.time.Instant.now();
                } else if (type == java.util.Date.class) {
                    return new java.util.Date();
                } else if (type == java.sql.Timestamp.class) {
                    return new java.sql.Timestamp(System.currentTimeMillis());
                } else if (type == java.sql.Date.class) {
                    return new java.sql.Date(System.currentTimeMillis());
                }
            } catch (Exception e) {
                // Ignore parsing errors, return null or original value
            }
        }

        return value;
    }

    /**
     * コレクションの型に応じて適切なインスタンスを作成する。
     */
    private Collection<Object> createCollectionInstance(Class<?> type) {
        if (type == List.class || type == ArrayList.class || type == Collection.class || type == Iterable.class) {
            return new ArrayList<>();
        } else if (type == LinkedList.class) {
            return new LinkedList<>();
        } else if (type == Set.class || type == HashSet.class) {
            return new HashSet<>();
        } else if (type == TreeSet.class) {
            return new TreeSet<>();
        }
        // デフォルトはArrayList
        return new ArrayList<>();
    }
}
