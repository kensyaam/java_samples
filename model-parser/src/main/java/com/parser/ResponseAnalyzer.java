package com.parser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.BorderFormatting;
import org.apache.poi.ss.usermodel.ConditionalFormattingRule;
import org.apache.poi.ss.usermodel.PatternFormatting;
import org.apache.poi.ss.usermodel.SheetConditionalFormatting;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtVariableWrite;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtParameterReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Spring MVC ControllerとJSPを解析して、レスポンス（画面表示）で
 * 実際に使用されているフィールドを特定するCLIツール。
 * 
 * <p>
 * レガシーシステムをREST API化する際、画面で実際に表示されている項目のみに
 * 絞ったスリムなAPIレスポンスを設計するためのデータを作成します。
 * </p>
 */
public class ResponseAnalyzer {

    // ==========================================================================
    // 定数定義
    // ==========================================================================

    /** Excelシート名 */
    private static final String SHEET_NAME = "ResponseAnalysis";

    /** 使用状況マーク */
    private static final String USED = "USED";
    private static final String UNUSED = "UNUSED";

    /** 属性の由来 */
    private static final String ORIGIN_ADD_ATTRIBUTE = "モデル:addAttribute";
    private static final String ORIGIN_PUT = "モデル:put";
    private static final String ORIGIN_ADD_OBJECT = "モデル:addObject";
    private static final String ORIGIN_ARGUMENT = "モデル:引数";

    /** フレームワーク型（メソッド引数から除外） */
    private static final Set<String> FRAMEWORK_TYPES = new HashSet<>(Arrays.asList(
            "Model", "ModelMap", "Map", "ModelAndView",
            "HttpServletRequest", "HttpServletResponse", "HttpSession",
            "BindingResult", "Errors", "RedirectAttributes",
            "Principal", "Authentication", "Locale",
            "String", "int", "Integer", "long", "Long", "boolean", "Boolean"));

    /** ハンドラーメソッドを示すアノテーション */
    private static final Set<String> HANDLER_ANNOTATIONS = new HashSet<>(Arrays.asList(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping"));

    /** EL (Expression Language) 式抽出用のパターン */
    private static final Pattern EL_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");

    /** JSPインクルードパターン */
    private static final Pattern JSP_INCLUDE_PATTERN = Pattern.compile(
            "<jsp:include\\s+page\\s*=\\s*[\"']([^\"']+)[\"']|" +
                    "<%@\\s*include\\s+file\\s*=\\s*[\"']([^\"']+)[\"']");

    /** スクリプトレットパターン */
    private static final Pattern SCRIPTLET_PATTERN = Pattern.compile("<%[^@=][^%]*%>");

    /** EL式でのスコープ参照パターン（requestScope, sessionScope, applicationScope, pageScope） */
    private static final Pattern EL_SCOPE_PATTERN = Pattern.compile(
            "\\$\\{(requestScope|sessionScope|applicationScope|pageScope)\\.([^}]+)\\}");

    /** スクリプトレット内でのスコープ参照パターン（request.getAttribute, session.getAttribute等） */
    private static final Pattern SCRIPTLET_SCOPE_PATTERN = Pattern.compile(
            "(request|session|application|pageContext)\\.getAttribute\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    /** チェーン呼び出しパターン（request.getSession().getAttribute） */
    private static final Pattern CHAIN_SCOPE_PATTERN = Pattern.compile(
            "request\\.getSession\\(\\)\\.getAttribute\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    // ==========================================================================
    // フォーム解析用定数
    // ==========================================================================

    /** Excelシート名（リクエスト解析用） */
    private static final String REQUEST_SHEET_NAME = "RequestAnalysis";

    /** Input Type（非入力要素） */
    private static final String NOT_INPUT_TYPE = "";

    /** HTML標準フォーム */
    private static final Pattern HTML_FORM_PATTERN = Pattern.compile(
            "<form(?:\\s+([^>]*))?>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Spring Form Tagフォーム */
    private static final Pattern SPRING_FORM_PATTERN = Pattern.compile(
            "<form:form\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Struts HTML Tagフォーム */
    private static final Pattern STRUTS_FORM_PATTERN = Pattern.compile(
            "<html:form\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 入力要素パターン（HTML標準） */
    private static final Pattern HTML_INPUT_PATTERN = Pattern.compile(
            "<(input|select|textarea|button)\\b([^>]*)(?:>(.*?)</\\1>|/?>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 入力要素パターン（Spring Form Tag） */
    private static final Pattern SPRING_INPUT_PATTERN = Pattern.compile(
            "<form:(input|password|hidden|checkbox|checkboxes|radiobutton|radiobuttons|"
                    + "select|option|options|textarea|errors|button)\\b([^>]*)(?:>(.*?)</form:\\1>|/?>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 入力要素パターン（Struts HTML Tag） */
    private static final Pattern STRUTS_INPUT_PATTERN = Pattern.compile(
            "<html:(text|password|hidden|checkbox|multibox|radio|select|submit|cancel|image|button"
                    + "|textarea|file)\\b([^>]*)(?:>(.*?)</html:\\1>|/?>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** 属性抽出用パターン */
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "(\\w+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.DOTALL);

    /** リンクタグパターン */
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "<(a|html:link)\\b([^>]*)(?:>(.*?)</(?:a|html:link)>|/?>)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** BODYタグパターン（onload取得用） */
    private static final Pattern BODY_PATTERN = Pattern.compile(
            "<body\\b([^>]*)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // ==========================================================================
    // 内部データクラス
    // ==========================================================================

    /**
     * Controller解析結果を保持するクラス。
     */
    private static class ControllerMethodInfo {
        final String controllerName;
        final String methodName;
        final List<String> viewPaths = new ArrayList<>(); // 複数のreturn対応
        final List<ModelAttribute> attributes = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        ControllerMethodInfo(String controllerName, String methodName) {
            this.controllerName = controllerName;
            this.methodName = methodName;
        }
    }

    /**
     * Model属性を保持するクラス。
     */
    private static class ModelAttribute {
        final String attributeName;
        final String javaClassName;
        final String origin;
        CtTypeReference<?> typeReference;

        ModelAttribute(String attributeName, String javaClassName, String origin) {
            this.attributeName = attributeName;
            this.javaClassName = javaClassName;
            this.origin = origin;
        }
    }

    /**
     * JSP解析結果を保持するクラス。
     */
    private static class JspAnalysisResult {
        final String jspPath;
        final Set<String> elExpressions = new HashSet<>();
        boolean hasScriptlets = false;
        final List<String> includedJsps = new ArrayList<>();

        // スコープ参照情報（元の表現、スコープ名、属性名を保持）
        final List<ScopeReference> scopeReferenceDetails = new ArrayList<>();

        // フォームでの使用フィールド（ModelAttribute名 -> Set<パス>）
        final Map<String, Set<String>> formUsages = new HashMap<>();

        JspAnalysisResult(String jspPath) {
            this.jspPath = jspPath;
        }
    }

    /**
     * スコープ参照の詳細情報を保持するクラス。
     */
    private static class ScopeReference {
        final String expression; // 元の表現（例: "${requestScope.userDto.userId}"）
        final String scopeName; // スコープ名（例: "requestスコープ"）
        final String attributeName; // 属性名（例: "userDto"）

        ScopeReference(String expression, String scopeName, String attributeName) {
            this.expression = expression;
            this.scopeName = scopeName;
            this.attributeName = attributeName;
        }
    }

    /**
     * レスポンス解析結果を保持するクラス。
     */
    private static class ResponseAnalysisResult {
        String controller;
        String method;
        String viewPath;
        String attributeName;
        String jspReference; // Attribute Nameの後に移動
        String javaClass;
        String javaField;
        String usageStatus;
        String attributeOrigin;
        String warning;

        ResponseAnalysisResult(String controller, String method, String viewPath,
                String attributeName, String jspReference, String javaClass, String javaField,
                String usageStatus, String attributeOrigin, String warning) {
            this.controller = controller;
            this.method = method;
            this.viewPath = viewPath;
            this.attributeName = attributeName;
            this.jspReference = jspReference;
            this.javaClass = javaClass;
            this.javaField = javaField;
            this.usageStatus = usageStatus;
            this.attributeOrigin = attributeOrigin;
            this.warning = warning;
        }
    }

    /**
     * CLIオプションを保持するクラス。
     */
    private static class CliOptions {
        List<String> sourceDirs = new ArrayList<>();
        List<String> classpathEntries = new ArrayList<>();
        int complianceLevel = 21;
        String encoding = "UTF-8";
        String jspRoot = null;
        String controllersFile = null;
        String outputFile = "response-analysis.xlsx";
    }

    /**
     * フォーム情報を保持するクラス。
     */
    private static class FormInfo {
        final String jspFilePath; // JSPファイルパス（相対パス）
        final String action; // action属性
        final String method; // method属性（GET/POST）
        final String rootModel; // modelAttribute/commandName
        final String formTag; // 使用しているタグ名（<form>, <form:form>等）
        final List<InputElementInfo> inputs = new ArrayList<>();

        FormInfo(String jspFilePath, String action, String method,
                String rootModel, String formTag) {
            this.jspFilePath = jspFilePath;
            this.action = action != null ? action : "";
            this.method = method != null ? method.toUpperCase() : "";
            this.rootModel = rootModel != null ? rootModel : "";
            this.formTag = formTag;
        }
    }

    /**
     * 入力要素情報を保持するクラス。
     */
    private static class InputElementInfo {
        final String inputTag; // タグ名（<input>, <form:input>等）
        final String parameterName; // name/path/property属性
        final String inputType; // type属性
        final String maxLength; // maxlength属性
        final boolean required; // required属性の有無
        final String jsonKeyEstimate; // パラメータ名の末尾（参考）
        final String nestPath; // パラメータ名の親パス（参考）
        final String events; // イベントハンドラ記述（onclick="...", onchange="..." 等）
        final String remarks; // 備考（value, href, body text等）

        public InputElementInfo(String inputTag, String parameterName, String inputType,
                String maxLength, boolean required, Map<String, String> events) {
            this(inputTag, parameterName, inputType, maxLength, required, events, null);
        }

        public InputElementInfo(String inputTag, String parameterName, String inputType,
                String maxLength, boolean required, Map<String, String> events, String remarks) {
            this.inputTag = inputTag;
            this.parameterName = parameterName != null ? parameterName : "";
            this.inputType = inputType != null ? inputType : "";
            this.maxLength = maxLength != null ? maxLength : "";
            this.required = required;
            // ドット記法を解析
            if (this.parameterName.contains(".")) {
                int lastDot = this.parameterName.lastIndexOf('.');
                this.nestPath = this.parameterName.substring(0, lastDot);
                this.jsonKeyEstimate = this.parameterName.substring(lastDot + 1);
            } else {
                this.nestPath = "";
                this.jsonKeyEstimate = this.parameterName;
            }

            // イベント情報を文字列化
            if (events != null && !events.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> entry : events.entrySet()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(entry.getKey()).append("=").append(entry.getValue());
                }
                this.events = sb.toString();
            } else {
                this.events = "";
            }

            this.remarks = remarks != null ? remarks : "";
        }
    }

    // ==========================================================================
    // インスタンス変数
    // ==========================================================================

    /** Spoonモデル */
    private CtModel spoonModel;

    /** JSPルートディレクトリ */
    private Path jspRootPath;

    /** JSPファイル読み込み時のエンコーディング */
    private Charset jspEncoding;

    /** 解析済みJSPキャッシュ */
    private final Map<String, JspAnalysisResult> jspCache = new HashMap<>();

    // ==========================================================================
    // メインエントリポイント
    // ==========================================================================

    public static void main(String[] args) {
        Options options = createOptions();

        try {
            CommandLineParser parser = new DefaultParser();
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                printHelp(options);
                System.exit(0);
            }

            CliOptions cliOptions = parseCommandLine(cmd);
            validateOptions(cliOptions, options);

            ResponseAnalyzer analyzer = new ResponseAnalyzer();
            analyzer.execute(cliOptions);

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
     * CLIオプションを定義する。
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

        options.addOption(Option.builder("j")
                .longOpt("jsp-root")
                .hasArg()
                .argName("dir")
                .desc("JSPルートディレクトリ（必須）")
                .build());

        options.addOption(Option.builder("c")
                .longOpt("controllers")
                .hasArg()
                .argName("file")
                .desc("対象Controllerリストファイル（必須、1行1クラス）")
                .build());

        options.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg()
                .argName("file")
                .desc("出力Excelファイル名（デフォルト: response-analysis.xlsx）")
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

        if (cmd.hasOption("s")) {
            String[] dirs = cmd.getOptionValue("s").split(",");
            for (String dir : dirs) {
                options.sourceDirs.add(dir.trim());
            }
        }

        if (cmd.hasOption("cp")) {
            String[] paths = cmd.getOptionValue("cp").split(",");
            for (String path : paths) {
                options.classpathEntries.add(path.trim());
            }
        }

        if (cmd.hasOption("cl")) {
            options.complianceLevel = Integer.parseInt(cmd.getOptionValue("cl"));
        }

        if (cmd.hasOption("e")) {
            options.encoding = cmd.getOptionValue("e");
        }

        if (cmd.hasOption("j")) {
            options.jspRoot = cmd.getOptionValue("j");
        }

        if (cmd.hasOption("c")) {
            options.controllersFile = cmd.getOptionValue("c");
        }

        if (cmd.hasOption("o")) {
            options.outputFile = cmd.getOptionValue("o");
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
        if (cliOptions.jspRoot == null) {
            errors.add("JSPルートディレクトリ (-j) は必須です。");
        }
        if (cliOptions.controllersFile == null) {
            errors.add("対象Controllerファイル (-c) は必須です。");
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
                "java -jar response-analyzer.jar",
                "\nSpring MVC ControllerとJSPを解析してレスポンスで使用されているフィールドを特定します。\n\n",
                options,
                "\n例:\n  java -jar response-analyzer.jar -s src/main/java -j src/main/webapp/WEB-INF/jsp -c controllers.txt -o response.xlsx\n",
                true);
    }

    // ==========================================================================
    // 実行ロジック
    // ==========================================================================

    /**
     * メイン実行ロジック。
     */
    private void execute(CliOptions options) throws IOException {
        System.out.println("=== Response Analyzer ===");
        System.out.println("Source directories: " + options.sourceDirs);
        System.out.println("JSP root: " + options.jspRoot);
        System.out.println("Controllers file: " + options.controllersFile);
        System.out.println("Output file: " + options.outputFile);
        System.out.println();

        this.jspRootPath = Paths.get(options.jspRoot);
        this.jspEncoding = Charset.forName(options.encoding);

        // 対象Controller一覧を読み込む
        Set<String> targetControllers = loadTargetClasses(options.controllersFile);
        System.out.println("[INFO] 対象Controller一覧を読み込みました: " + targetControllers.size() + " 件");
        for (String className : targetControllers) {
            System.out.println("  - " + className);
        }
        System.out.println();

        // Spoonモデルを構築
        System.out.println("[INFO] ソースコードを解析中...");
        this.spoonModel = buildSpoonModel(options);
        System.out.println("[INFO] ソースコード解析完了");
        System.out.println();

        // Controllerを解析
        System.out.println("[INFO] Controller解析を開始します");
        System.out.println("----------------------------------------");
        List<ControllerMethodInfo> methodInfos = new ArrayList<>();

        for (CtType<?> type : spoonModel.getAllTypes()) {
            String qualifiedName = type.getQualifiedName();
            String simpleName = type.getSimpleName();

            if (targetControllers.contains(qualifiedName) || targetControllers.contains(simpleName)) {
                System.out.println("[Controller] " + qualifiedName);
                List<ControllerMethodInfo> infos = analyzeController(type);
                methodInfos.addAll(infos);
                System.out.println("  -> " + infos.size() + " ハンドラーメソッドを検出");
            }
        }
        System.out.println("----------------------------------------");
        System.out.println();

        // JSP解析とマッピング
        System.out.println("[INFO] JSP解析とマッピングを開始します");
        List<ResponseAnalysisResult> results = new ArrayList<>();

        for (ControllerMethodInfo methodInfo : methodInfos) {
            // 複数のreturn文がある場合はそれぞれについて解析
            List<String> viewPathsToAnalyze = methodInfo.viewPaths;
            if (viewPathsToAnalyze.isEmpty()) {
                // View名が特定できなかった場合は警告を追加
                for (ModelAttribute attr : methodInfo.attributes) {
                    results.add(new ResponseAnalysisResult(
                            methodInfo.controllerName,
                            methodInfo.methodName,
                            "",
                            attr.attributeName,
                            "",
                            attr.javaClassName,
                            "",
                            "",
                            attr.origin,
                            "View名を特定できませんでした"));
                }
                continue;
            }

            // 各View名について解析
            for (String viewPath : viewPathsToAnalyze) {
                // JSPを解析
                JspAnalysisResult jspResult = analyzeJsp(viewPath);
                if (jspResult == null) {
                    for (ModelAttribute attr : methodInfo.attributes) {
                        results.add(new ResponseAnalysisResult(
                                methodInfo.controllerName,
                                methodInfo.methodName,
                                viewPath,
                                attr.attributeName,
                                "",
                                attr.javaClassName,
                                "",
                                "",
                                attr.origin,
                                "JSPファイルが見つかりませんでした"));
                    }
                    continue;
                }

                // 警告を構築
                List<String> warnings = new ArrayList<>();
                if (jspResult.hasScriptlets) {
                    warnings.add("スクリプトレットが含まれています");
                }
                // インクルードファイルを警告列に追加
                if (!jspResult.includedJsps.isEmpty()) {
                    warnings.add("Include: " + String.join(", ", jspResult.includedJsps));
                }
                String warningStr = String.join("; ", warnings);

                // マッチしたEL式とフォームパスを追跡するセット
                Set<String> handledEls = new HashSet<>();
                Set<String> handledFormPaths = new HashSet<>();

                // 各属性についてフィールドの使用状況を解析
                for (ModelAttribute attr : methodInfo.attributes) {
                    List<ResponseAnalysisResult> attrResults = matchAttributeToJsp(
                            methodInfo, attr, jspResult, viewPath, warningStr, handledEls, handledFormPaths);
                    results.addAll(attrResults);
                }

                // スコープ参照を独立した行として追加
                for (ScopeReference scopeRef : jspResult.scopeReferenceDetails) {
                    handledEls.add(scopeRef.expression); // スコープ参照も処理済みとする
                    results.add(new ResponseAnalysisResult(
                            methodInfo.controllerName,
                            methodInfo.methodName,
                            viewPath,
                            scopeRef.attributeName, // Attribute Name: 属性名
                            scopeRef.expression, // JSP Reference: 元の表現
                            "", // Java Class: 空
                            "", // Java Field: 空
                            "", // 使用状況: 空（スコープ参照なので判定しない）
                            scopeRef.scopeName, // 属性の由来: "requestスコープ"等
                            warningStr));
                }

                // JSPにのみ存在する項目（属性にもスコープ参照にも該当しないEL式）を追加
                for (String el : jspResult.elExpressions) {
                    if (!handledEls.contains(el)) {
                        // ${foo.bar} -> foo
                        String elContent = el.substring(2, el.length() - 1).trim();
                        String attributeName = elContent.contains(".") ? elContent.substring(0, elContent.indexOf('.'))
                                : elContent;

                        results.add(new ResponseAnalysisResult(
                                methodInfo.controllerName,
                                methodInfo.methodName,
                                viewPath,
                                attributeName, // Attribute Name: 推定される属性名
                                el, // JSP Reference: EL式
                                "", // Java Class: 空
                                "", // Java Field: 空
                                "", // 使用状況: 空
                                "JSPのみ", // 属性の由来
                                warningStr));
                    }
                }

                // JSPにのみ存在する項目（属性にも該当しないフォームパス）を追加
                for (Map.Entry<String, Set<String>> entry : jspResult.formUsages.entrySet()) {
                    String modelName = entry.getKey();
                    for (String path : entry.getValue()) {
                        String fullPath = modelName + "." + path;
                        if (!handledFormPaths.contains(fullPath)) {
                            results.add(new ResponseAnalysisResult(
                                    methodInfo.controllerName,
                                    methodInfo.methodName,
                                    viewPath,
                                    modelName, // Attribute Name: modelAttribute名
                                    "Form: path=\"" + path + "\"", // JSP Reference: フォームパス
                                    "", // Java Class: 空
                                    "", // Java Field: 空
                                    "", // 使用状況: 空
                                    "JSPのみ(Form)", // 属性の由来
                                    warningStr));
                        }
                    }
                }
            }
        }
        System.out.println("----------------------------------------");
        System.out.println();

        System.out.println("[INFO] 解析完了: " + results.size() + " 件の結果");

        // JSPフォーム解析
        System.out.println();
        System.out.println("[INFO] JSPフォーム解析を開始...");
        List<FormInfo> formResults = analyzeAllJspForms();
        int inputCount = formResults.stream().mapToInt(f -> f.inputs.size()).sum();
        System.out.println("[INFO] フォーム解析完了: " + formResults.size() + " 件のフォーム, " + inputCount + " 件の入力要素");

        // Excelファイルを生成
        System.out.println();
        System.out.println("[INFO] Excelファイルを生成中...");
        generateExcel(results, formResults, options.outputFile);
        System.out.println("[INFO] Excelファイル生成完了: " + options.outputFile);
    }

    /**
     * 対象クラス一覧ファイルを読み込む。
     */
    private Set<String> loadTargetClasses(String filePath) throws IOException {
        Set<String> classes = new HashSet<>();
        Path path = Paths.get(filePath);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(path.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
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

        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(options.complianceLevel);
        launcher.getEnvironment().setSourceOutputDirectory(new File("spooned"));
        launcher.getEnvironment().setCommentEnabled(true);
        launcher.getEnvironment().setEncoding(Charset.forName(options.encoding));

        for (String dir : options.sourceDirs) {
            launcher.addInputResource(dir);
        }

        // クラスパスを構築
        List<String> classpathList = new ArrayList<>();
        for (String entry : options.classpathEntries) {
            File file = new File(entry);
            if (file.isDirectory()) {
                classpathList.add(file.getAbsolutePath());
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

        launcher.buildModel();
        return launcher.getModel();
    }

    // ==========================================================================
    // Controller解析ロジック
    // ==========================================================================

    /**
     * Controllerを解析してハンドラーメソッド情報を抽出する。
     */
    private List<ControllerMethodInfo> analyzeController(CtType<?> type) {
        List<ControllerMethodInfo> results = new ArrayList<>();

        // このクラスと親クラス・インターフェースを走査（親が先に来るようにLinkedHashSetを使用）
        Set<CtType<?>> typesToAnalyze = new LinkedHashSet<>();
        collectTypeHierarchy(type, typesToAnalyze);

        for (CtType<?> t : typesToAnalyze) {
            for (CtMethod<?> method : t.getMethods()) {
                if (isHandlerMethod(method)) {
                    // ボディがない場合（インターフェース/抽象メソッド）は実装メソッドを探す
                    CtMethod<?> targetMethod = method;
                    if (method.getBody() == null) {
                        CtMethod<?> implMethod = findImplementingMethod(type, method);
                        if (implMethod != null) {
                            targetMethod = implMethod;
                        } else {
                            // 実装メソッドが見つからない場合は警告を出してスキップするか、
                            // そのまま解析を試みる（結果的にviewNamesは空になる）
                            // ここでは実装が見つからない場合はスキップする
                            continue;
                        }
                    }
                    ControllerMethodInfo info = analyzeHandlerMethod(type.getSimpleName(), targetMethod);
                    results.add(info);
                }
            }
        }

        return results;
    }

    /**
     * インターフェースまたは抽象クラスのメソッドに対応する実装メソッドを探す。
     * 
     * @param implementingType 実装を持つ可能性のある型（対象Controller）
     * @param abstractMethod   抽象メソッドまたはインターフェースのメソッド
     * @return 実装メソッド、見つからない場合はnull
     */
    private CtMethod<?> findImplementingMethod(CtType<?> implementingType, CtMethod<?> abstractMethod) {
        String methodName = abstractMethod.getSimpleName();
        int paramCount = abstractMethod.getParameters().size();

        // 実装クラスのメソッドを検索
        // 注意: paramCountだけでなく型の一致も厳密には必要だが、簡易的に名前と引数数で判定
        for (CtMethod<?> method : implementingType.getMethods()) {
            if (method.getSimpleName().equals(methodName)
                    && method.getParameters().size() == paramCount
                    && method.getBody() != null) {
                return method;
            }
        }
        return null;
    }

    /**
     * 型階層（親クラス・インターフェース）を再帰的に収集する。
     */
    private void collectTypeHierarchy(CtType<?> type, Set<CtType<?>> collected) {
        if (type == null || collected.contains(type)) {
            return;
        }

        // 親クラスを先に収集（再帰）
        CtTypeReference<?> superclass = type.getSuperclass();
        if (superclass != null) {
            CtType<?> superType = superclass.getTypeDeclaration();
            if (superType != null) {
                collectTypeHierarchy(superType, collected);
            }
        }

        // インターフェースを収集
        for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
            CtType<?> ifaceType = iface.getTypeDeclaration();
            if (ifaceType != null) {
                collectTypeHierarchy(ifaceType, collected);
            }
        }

        // 最後に自分自身を追加することで、親から順に並ぶようにする
        collected.add(type);
    }

    /**
     * メソッドがハンドラーメソッドかどうかを判定する。
     */
    private boolean isHandlerMethod(CtMethod<?> method) {
        for (CtAnnotation<?> annotation : method.getAnnotations()) {
            String name = annotation.getAnnotationType().getSimpleName();
            if (HANDLER_ANNOTATIONS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ハンドラーメソッドを解析する。
     */
    private ControllerMethodInfo analyzeHandlerMethod(String controllerName, CtMethod<?> method) {
        ControllerMethodInfo info = new ControllerMethodInfo(controllerName, method.getSimpleName());

        // View名を特定（複数return対応）
        List<String> viewNames = extractAllViewNames(method);
        info.viewPaths.addAll(viewNames);

        // Model属性を抽出（明示的）
        extractExplicitModelAttributes(method, info);

        // Model属性を抽出（暗黙的 - メソッド引数）
        extractImplicitModelAttributes(method, info);

        return info;
    }

    /**
     * メソッド内の全てのreturn文からView名を抽出する（複数return対応）。
     */
    private List<String> extractAllViewNames(CtMethod<?> method) {
        Set<String> viewNames = new HashSet<>();

        // StringまたはModelAndViewを返すメソッドを対象
        CtTypeReference<?> returnType = method.getType();
        if (returnType == null) {
            return new ArrayList<>();
        }

        String returnTypeName = returnType.getSimpleName();
        if (!returnTypeName.equals("String") && !returnTypeName.equals("ModelAndView")) {
            return new ArrayList<>();
        }

        // return文を全て取得
        List<CtReturn<?>> returns = method.getElements(new TypeFilter<>(CtReturn.class));
        for (CtReturn<?> ret : returns) {
            CtExpression<?> expr = ret.getReturnedExpression();
            if (expr == null) {
                continue;
            }

            // 式から全ての候補値を取得
            List<String> resolvedNames = resolveAllExpressions(expr, method);
            for (String viewName : resolvedNames) {
                if (viewName != null && !viewName.isEmpty()) {
                    // "redirect:"や"forward:"で始まる場合はスキップ
                    if (!viewName.startsWith("redirect:") && !viewName.startsWith("forward:")) {
                        viewNames.add(viewName);
                    }
                }
            }
        }

        return new ArrayList<>(viewNames);
    }

    /**
     * 式を解決して全ての候補文字列値を取得する。
     * 変数の場合は、宣言時の初期化と全ての代入文の値を候補として返す。
     * 
     * @param expr   解析対象の式
     * @param method 変数追跡用のメソッドコンテキスト（nullの場合は変数追跡しない）
     */
    private List<String> resolveAllExpressions(CtExpression<?> expr, CtMethod<?> method) {
        return resolveAllExpressions(expr, method, new HashMap<>());
    }

    /**
     * 式を解決して全ての候補文字列値を取得する（引数マッピング付き）。
     * 変数の場合は、宣言時の初期化と全ての代入文の値を候補として返す。
     * メソッド呼び出しの場合は、引数をパラメータにマッピングして追跡する。
     * 
     * @param expr        解析対象の式
     * @param method      変数追跡用のメソッドコンテキスト（nullの場合は変数追跡しない）
     * @param paramValues パラメータ名から値へのマッピング（メソッド引数の追跡用）
     */
    private List<String> resolveAllExpressions(CtExpression<?> expr, CtMethod<?> method,
            Map<String, List<String>> paramValues) {
        List<String> results = new ArrayList<>();

        // リテラルの場合
        if (expr instanceof CtLiteral<?>) {
            Object value = ((CtLiteral<?>) expr).getValue();
            if (value != null) {
                results.add(value.toString());
            }
            return results;
        }

        // 二項演算子（文字列連結など）の場合
        if (expr instanceof CtBinaryOperator<?>) {
            CtBinaryOperator<?> binOp = (CtBinaryOperator<?>) expr;
            // 文字列連結の場合（PLUS演算子）
            if (binOp.getKind() == spoon.reflect.code.BinaryOperatorKind.PLUS) {
                List<String> leftValues = resolveAllExpressions(binOp.getLeftHandOperand(), method, paramValues);
                List<String> rightValues = resolveAllExpressions(binOp.getRightHandOperand(), method, paramValues);

                // 両辺の値を組み合わせて連結
                if (!leftValues.isEmpty() && !rightValues.isEmpty()) {
                    for (String left : leftValues) {
                        for (String right : rightValues) {
                            results.add(left + right);
                        }
                    }
                } else if (!leftValues.isEmpty()) {
                    // 右辺が解決できない場合は左辺のみ
                    results.addAll(leftValues);
                } else if (!rightValues.isEmpty()) {
                    // 左辺が解決できない場合は右辺のみ
                    results.addAll(rightValues);
                }
                return results;
            }
        }

        // フィールド参照（定数）の場合
        if (expr instanceof CtFieldRead<?>) {
            CtFieldRead<?> fieldRead = (CtFieldRead<?>) expr;
            CtFieldReference<?> fieldRef = fieldRead.getVariable();
            if (fieldRef != null) {
                CtField<?> field = fieldRef.getFieldDeclaration();

                // getFieldDeclaration()がnullの場合、Spoonモデルから検索を試みる
                if (field == null && spoonModel != null) {
                    field = findFieldInModel(fieldRef);
                }

                if (field != null && field.getDefaultExpression() != null) {
                    return resolveAllExpressions(field.getDefaultExpression(), method, paramValues);
                }
            }
            return results;
        }

        // パラメータ参照の場合 - 引数マッピングから値を取得
        if (expr instanceof CtVariableRead<?>) {
            CtVariableRead<?> varRead = (CtVariableRead<?>) expr;
            if (varRead.getVariable() instanceof CtParameterReference<?>) {
                CtParameterReference<?> paramRef = (CtParameterReference<?>) varRead.getVariable();
                String paramName = paramRef.getSimpleName();
                if (paramValues.containsKey(paramName)) {
                    results.addAll(paramValues.get(paramName));
                    return results;
                }
            }
        }

        // ローカル変数参照の場合 - 変数への代入をすべて追跡
        if (expr instanceof CtVariableRead<?> && method != null) {
            CtVariableRead<?> varRead = (CtVariableRead<?>) expr;
            if (varRead.getVariable() instanceof CtLocalVariableReference<?>) {
                CtLocalVariableReference<?> localVarRef = (CtLocalVariableReference<?>) varRead.getVariable();
                String varName = localVarRef.getSimpleName();

                // メソッド内のローカル変数宣言を検索（初期化値）
                List<CtLocalVariable<?>> localVars = method.getElements(new TypeFilter<>(CtLocalVariable.class));
                for (CtLocalVariable<?> localVar : localVars) {
                    if (localVar.getSimpleName().equals(varName) && localVar.getDefaultExpression() != null) {
                        List<String> resolved = resolveAllExpressions(localVar.getDefaultExpression(), method,
                                paramValues);
                        results.addAll(resolved);
                    }
                }

                // 代入文を全て検索（すべての代入値を候補に追加）
                List<CtAssignment<?, ?>> assignments = method.getElements(new TypeFilter<>(CtAssignment.class));
                for (CtAssignment<?, ?> assignment : assignments) {
                    // 代入文の左辺はCtVariableWrite
                    if (assignment.getAssigned() instanceof CtVariableWrite<?>) {
                        CtVariableWrite<?> assigned = (CtVariableWrite<?>) assignment.getAssigned();
                        if (assigned.getVariable().getSimpleName().equals(varName)) {
                            List<String> resolved = resolveAllExpressions(assignment.getAssignment(), null,
                                    paramValues);
                            results.addAll(resolved);
                        }
                    }
                }
                return results;
            }
        }

        // メソッド呼び出しの場合 - 呼び出し先メソッドのreturn文を解析
        if (expr instanceof CtInvocation<?>) {
            CtInvocation<?> invocation = (CtInvocation<?>) expr;
            CtExecutableReference<?> execRef = invocation.getExecutable();
            if (execRef != null) {
                CtExecutable<?> executable = execRef.getExecutableDeclaration();

                // getExecutableDeclaration()がnullの場合、Spoonモデルから検索を試みる
                if (executable == null && spoonModel != null) {
                    executable = findMethodInModel(execRef);
                }

                // 呼び出し先がCtMethod（解析可能なメソッド）の場合
                if (executable instanceof CtMethod<?>) {
                    CtMethod<?> calledMethod = (CtMethod<?>) executable;

                    // 引数とパラメータのマッピングを構築
                    Map<String, List<String>> argMapping = new HashMap<>();
                    List<CtExpression<?>> args = invocation.getArguments();
                    List<CtParameter<?>> params = calledMethod.getParameters();
                    for (int i = 0; i < Math.min(args.size(), params.size()); i++) {
                        String paramName = params.get(i).getSimpleName();
                        // 引数の値を解決（現在のコンテキストで）
                        List<String> argValues = resolveAllExpressions(args.get(i), method, paramValues);
                        if (!argValues.isEmpty()) {
                            argMapping.put(paramName, argValues);
                        }
                    }

                    // 呼び出し先メソッドのreturn文を解析
                    List<CtReturn<?>> calledReturns = calledMethod.getElements(new TypeFilter<>(CtReturn.class));
                    for (CtReturn<?> ret : calledReturns) {
                        CtExpression<?> retExpr = ret.getReturnedExpression();
                        if (retExpr != null) {
                            // 再帰的に解決（引数マッピングを渡す）
                            List<String> resolved = resolveAllExpressions(retExpr, calledMethod, argMapping);
                            results.addAll(resolved);
                        }
                    }
                }
            }
            return results;
        }

        return results;
    }

    /**
     * Spoonモデル全体からメソッドを検索する。
     * getExecutableDeclaration()がnullを返す場合のフォールバック。
     * 
     * @param execRef 検索するメソッドの参照
     * @return 見つかったメソッド、見つからない場合はnull
     */
    private CtMethod<?> findMethodInModel(CtExecutableReference<?> execRef) {
        String methodName = execRef.getSimpleName();
        CtTypeReference<?> declaringType = execRef.getDeclaringType();

        if (declaringType == null) {
            return null;
        }

        String typeName = declaringType.getQualifiedName();

        // モデル内の全ての型を検索
        for (CtType<?> type : spoonModel.getAllTypes()) {
            // 宣言型と一致するか、親クラス・インターフェースに含まれるかをチェック
            if (type.getQualifiedName().equals(typeName) || isSubtypeOf(type, typeName)) {
                // メソッドを検索
                for (CtMethod<?> m : type.getMethods()) {
                    if (m.getSimpleName().equals(methodName)) {
                        // パラメータ数もチェック
                        if (m.getParameters().size() == execRef.getParameters().size()) {
                            return m;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * 型が指定された型名のサブタイプかどうかをチェックする。
     */
    private boolean isSubtypeOf(CtType<?> type, String superTypeName) {
        // 親クラスをチェック
        CtTypeReference<?> superclass = type.getSuperclass();
        if (superclass != null && superclass.getQualifiedName().equals(superTypeName)) {
            return true;
        }

        // インターフェースをチェック
        for (CtTypeReference<?> iface : type.getSuperInterfaces()) {
            if (iface.getQualifiedName().equals(superTypeName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Spoonモデル全体からフィールドを検索する。
     * getFieldDeclaration()がnullを返す場合のフォールバック。
     * 
     * @param fieldRef 検索するフィールドの参照
     * @return 見つかったフィールド、見つからない場合はnull
     */
    private CtField<?> findFieldInModel(CtFieldReference<?> fieldRef) {
        String fieldName = fieldRef.getSimpleName();
        CtTypeReference<?> declaringType = fieldRef.getDeclaringType();

        if (declaringType == null) {
            return null;
        }

        String typeName = declaringType.getQualifiedName();

        // モデル内の全ての型を検索
        for (CtType<?> type : spoonModel.getAllTypes()) {
            // 宣言型と一致するか、親クラス・インターフェースに含まれるかをチェック
            if (type.getQualifiedName().equals(typeName) || isSubtypeOf(type, typeName)) {
                // フィールドを検索
                for (CtField<?> f : type.getFields()) {
                    if (f.getSimpleName().equals(fieldName)) {
                        return f;
                    }
                }
            }
        }

        return null;
    }

    /**
     * 式を解決して文字列値を取得する（単一の値を返す、後方互換用）。
     */
    private String resolveExpression(CtExpression<?> expr) {
        List<String> results = resolveAllExpressions(expr, null);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 明示的なModel属性を抽出する（addAttribute, put, addObject）。
     */
    private void extractExplicitModelAttributes(CtMethod<?> method, ControllerMethodInfo info) {
        List<CtInvocation<?>> invocations = method.getElements(new TypeFilter<>(CtInvocation.class));

        for (CtInvocation<?> invocation : invocations) {
            String methodName = invocation.getExecutable().getSimpleName();

            // addAttribute, addObject, put のいずれかをチェック
            if (!methodName.equals("addAttribute") && !methodName.equals("addObject") && !methodName.equals("put")) {
                continue;
            }

            // 引数を取得
            List<CtExpression<?>> args = invocation.getArguments();
            if (args.size() < 2) {
                continue;
            }

            // 第一引数（属性名）を取得
            String attrName = resolveExpression(args.get(0));
            if (attrName == null || attrName.isEmpty()) {
                continue;
            }

            // 第二引数（オブジェクト）の型を取得
            CtExpression<?> valueExpr = args.get(1);
            CtTypeReference<?> valueType = valueExpr.getType();
            String className = valueType != null ? valueType.getSimpleName() : "unknown";

            // 由来を決定
            String origin;
            if (methodName.equals("addAttribute")) {
                origin = ORIGIN_ADD_ATTRIBUTE;
            } else if (methodName.equals("addObject")) {
                origin = ORIGIN_ADD_OBJECT;
            } else {
                origin = ORIGIN_PUT;
            }

            ModelAttribute attr = new ModelAttribute(attrName, className, origin);
            attr.typeReference = valueType;
            info.attributes.add(attr);
        }
    }

    /**
     * 暗黙的なModel属性を抽出する（メソッド引数）。
     */
    private void extractImplicitModelAttributes(CtMethod<?> method, ControllerMethodInfo info) {
        for (CtParameter<?> param : method.getParameters()) {
            CtTypeReference<?> paramType = param.getType();
            if (paramType == null) {
                continue;
            }

            String typeName = paramType.getSimpleName();

            // フレームワーク型は除外
            if (FRAMEWORK_TYPES.contains(typeName)) {
                continue;
            }

            // 属性名を決定
            String attrName = null;

            // @ModelAttribute アノテーションをチェック
            for (CtAnnotation<?> annotation : param.getAnnotations()) {
                if (annotation.getAnnotationType().getSimpleName().equals("ModelAttribute")) {
                    Object value = annotation.getValue("value");
                    if (value != null && !value.toString().isEmpty()) {
                        attrName = value.toString();
                        // 引用符を除去
                        if (attrName.startsWith("\"") && attrName.endsWith("\"")) {
                            attrName = attrName.substring(1, attrName.length() - 1);
                        }
                    }
                    break;
                }
            }

            // @ModelAttributeがない場合はクラス名の先頭を小文字にしたもの
            if (attrName == null || attrName.isEmpty()) {
                attrName = Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
            }

            ModelAttribute attr = new ModelAttribute(attrName, typeName, ORIGIN_ARGUMENT);
            attr.typeReference = paramType;
            info.attributes.add(attr);
        }
    }

    // ==========================================================================
    // JSP解析ロジック
    // ==========================================================================

    /**
     * JSPファイルを解析する。
     */
    private JspAnalysisResult analyzeJsp(String viewName) {
        // キャッシュをチェック
        if (jspCache.containsKey(viewName)) {
            return jspCache.get(viewName);
        }

        // JSPファイルパスを構築
        String jspFileName = viewName.endsWith(".jsp") ? viewName : viewName + ".jsp";
        Path jspPath = jspRootPath.resolve(jspFileName);

        if (!Files.exists(jspPath)) {
            System.out.println("  [WARNING] JSPファイルが見つかりません: " + jspPath);
            return null;
        }

        System.out.println("  [JSP] " + jspPath);

        JspAnalysisResult result = new JspAnalysisResult(jspPath.toString());

        try {
            String content = Files.readString(jspPath, jspEncoding);

            // EL式を抽出
            Matcher elMatcher = EL_PATTERN.matcher(content);
            while (elMatcher.find()) {
                result.elExpressions.add(elMatcher.group(0));
            }

            // スクリプトレットをチェック
            Matcher scriptletMatcher = SCRIPTLET_PATTERN.matcher(content);
            if (scriptletMatcher.find()) {
                result.hasScriptlets = true;
            }

            // インクルードを処理（再帰）
            Matcher includeMatcher = JSP_INCLUDE_PATTERN.matcher(content);
            while (includeMatcher.find()) {
                String includePath = includeMatcher.group(1);
                if (includePath == null) {
                    includePath = includeMatcher.group(2);
                }
                if (includePath != null) {
                    result.includedJsps.add(includePath);
                    // 再帰的に解析
                    JspAnalysisResult includedResult = analyzeJsp(includePath);
                    if (includedResult != null) {
                        result.elExpressions.addAll(includedResult.elExpressions);
                        if (includedResult.hasScriptlets) {
                            result.hasScriptlets = true;
                        }
                        // スコープ参照をマージ
                        result.scopeReferenceDetails.addAll(includedResult.scopeReferenceDetails);
                        // フォーム使用状況をマージ
                        for (Map.Entry<String, Set<String>> entry : includedResult.formUsages.entrySet()) {
                            result.formUsages.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                                    .addAll(entry.getValue());
                        }
                    }
                }
            }

            // EL式でのスコープ参照を抽出
            Matcher elScopeMatcher = EL_SCOPE_PATTERN.matcher(content);
            while (elScopeMatcher.find()) {
                String fullMatch = elScopeMatcher.group(0); // ${requestScope.userDto.userId}
                String scope = elScopeMatcher.group(1); // requestScope
                String attrExpr = elScopeMatcher.group(2); // userDto.userId
                String attrName = attrExpr.contains(".") ? attrExpr.substring(0, attrExpr.indexOf('.')) : attrExpr;
                String scopeNameJp = toJapaneseScopeName(scope);
                result.scopeReferenceDetails.add(new ScopeReference(fullMatch, scopeNameJp, attrName));
            }

            // スクリプトレット内でのスコープ参照を抽出
            Matcher scriptletScopeMatcher = SCRIPTLET_SCOPE_PATTERN.matcher(content);
            while (scriptletScopeMatcher.find()) {
                String fullMatch = scriptletScopeMatcher.group(0); // request.getAttribute("userDto")
                String scopeObj = scriptletScopeMatcher.group(1); // request
                String attrName = scriptletScopeMatcher.group(2); // userDto
                String scopeNameJp = toJapaneseScopeName(mapObjectToScope(scopeObj));
                result.scopeReferenceDetails.add(new ScopeReference(fullMatch, scopeNameJp, attrName));
            }

            // チェーン呼び出しパターン（request.getSession().getAttribute）
            Matcher chainMatcher = CHAIN_SCOPE_PATTERN.matcher(content);
            while (chainMatcher.find()) {
                String fullMatch = chainMatcher.group(0); // request.getSession().getAttribute("userDto")
                String attrName = chainMatcher.group(1); // userDto
                result.scopeReferenceDetails.add(new ScopeReference(fullMatch, "sessionスコープ", attrName));
            }

            // フォーム定義からモデル属性の使用を抽出
            List<FormInfo> forms = analyzeJspForms(jspPath);
            for (FormInfo form : forms) {
                if (form.rootModel != null && !form.rootModel.isEmpty()) {
                    Set<String> usedPaths = result.formUsages.computeIfAbsent(form.rootModel, k -> new HashSet<>());
                    for (InputElementInfo input : form.inputs) {
                        if (input.parameterName != null && !input.parameterName.isEmpty()) {
                            usedPaths.add(input.parameterName);
                        }
                    }
                }
            }

        } catch (IOException e) {
            System.out.println("  [ERROR] JSPファイル読み込みエラー: " + e.getMessage());
            return null;
        }

        jspCache.put(viewName, result);
        return result;
    }

    // ==========================================================================
    // フォーム解析ロジック
    // ==========================================================================

    /**
     * 指定ディレクトリ配下のJSPファイルを全走査してフォームを解析する。
     */
    private List<FormInfo> analyzeAllJspForms() {
        List<FormInfo> allForms = new ArrayList<>();
        try {
            Files.walk(jspRootPath)
                    .filter(p -> p.toString().toLowerCase().endsWith(".jsp"))
                    .forEach(jspPath -> {
                        List<FormInfo> forms = analyzeJspForms(jspPath);
                        allForms.addAll(forms);
                    });
        } catch (IOException e) {
            System.out.println("[ERROR] JSPディレクトリの走査に失敗: " + e.getMessage());
        }
        return allForms;
    }

    /**
     * 単一JSPファイル内のフォームを解析する。
     */
    private List<FormInfo> analyzeJspForms(Path jspPath) {
        List<FormInfo> forms = new ArrayList<>();

        // 相対パスを取得
        String relativePath = jspRootPath.relativize(jspPath).toString().replace("\\", "/");

        try {
            String content = Files.readString(jspPath, jspEncoding);
            StringBuilder maskBuffer = new StringBuilder(content);

            // 1. フォームの解析 (解析済み領域はmaskBufferでスペースに置換)
            // HTML標準フォーム
            forms.addAll(extractFormsWithPattern(content, relativePath,
                    HTML_FORM_PATTERN, "</form>", "<form>", "html", maskBuffer));

            // Spring Form Tag
            forms.addAll(extractFormsWithPattern(content, relativePath,
                    SPRING_FORM_PATTERN, "</form:form>", "<form:form>", "spring", maskBuffer));

            // Struts HTML Tag
            forms.addAll(extractFormsWithPattern(content, relativePath,
                    STRUTS_FORM_PATTERN, "</html:form>", "<html:form>", "struts", maskBuffer));

            // 2. 非入力要素およびフォーム外要素の解析
            // リンクは元のコンテンツから（フォーム内外関わらず）抽出
            // フォーム外入力要素はマスクされたコンテンツから抽出
            extractNotInputsWithPattern(content, maskBuffer.toString(), relativePath, forms);

        } catch (IOException e) {
            System.out.println("  [ERROR] JSPファイル読み込みエラー: " + jspPath + " - " + e.getMessage());
        }

        if (forms.isEmpty()) {
            // フォームがない場合も、ファイル自体をリストに出力するためのダミーエントリを追加
            forms.add(new FormInfo(relativePath, "", "", "", "(no form)"));
        }

        return forms;
    }

    /**
     * 指定パターンでフォームを抽出する。
     */
    private List<FormInfo> extractFormsWithPattern(String content, String relativePath,
            Pattern formPattern, String endTag, String formTagDisplay, String tagType,
            StringBuilder maskBuffer) {
        List<FormInfo> forms = new ArrayList<>();

        Matcher formMatcher = formPattern.matcher(content);
        while (formMatcher.find()) {
            int formStart = formMatcher.end();
            int matchStart = formMatcher.start();

            String formAttrs = formMatcher.group(1);
            Map<String, String> attrs = extractAttributes(formAttrs);

            // 属性取得
            String action = attrs.get("action");
            String defaultMethod = "html".equalsIgnoreCase(tagType) ? "GET" : "POST";
            String method = attrs.getOrDefault("method", defaultMethod);

            // ルートモデル（Spring: modelAttribute/commandName）
            String rootModel = attrs.get("modelattribute");
            if (rootModel == null) {
                rootModel = attrs.get("commandname");
            }

            FormInfo formInfo = new FormInfo(relativePath, action, method, rootModel, formTagDisplay);

            // フォーム自体のイベントを取得
            Map<String, String> formEvents = new HashMap<>();
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                if (entry.getKey().startsWith("on")) {
                    formEvents.put(entry.getKey(), entry.getValue());
                }
            }
            if (!formEvents.isEmpty()) {
                formInfo.inputs.add(new InputElementInfo(formTagDisplay, "", "form", "", false, formEvents));
            }

            // フォーム終了位置を探す
            int formEnd = content.toLowerCase().indexOf(endTag.toLowerCase(), formStart);
            int matchEnd;
            if (formEnd == -1) {
                formEnd = content.length();
                matchEnd = content.length();
            } else {
                matchEnd = formEnd + endTag.length();
            }
            String formContent = content.substring(formStart, formEnd);

            // 入力要素を抽出
            extractInputElements(formContent, formInfo, tagType);

            forms.add(formInfo);

            // 解析済み領域をマスク（スペースで埋める）
            for (int i = matchStart; i < matchEnd; i++) {
                if (i < maskBuffer.length()) { // 安全策
                    maskBuffer.setCharAt(i, ' ');
                }
            }
        }

        return forms;
    }

    /**
     * フォーム内の入力要素を抽出する。
     */
    private void extractInputElements(String formContent, FormInfo formInfo, String tagType) {

        // HTML標準入力要素
        extractInputsWithPattern(formContent, formInfo, HTML_INPUT_PATTERN, "html");

        // Spring Form Tag入力要素
        if ("spring".equals(tagType) || "html".equals(tagType)) {
            extractInputsWithPattern(formContent, formInfo, SPRING_INPUT_PATTERN, "spring");
        }

        // Struts HTML Tag入力要素
        if ("struts".equals(tagType) || "html".equals(tagType)) {
            extractInputsWithPattern(formContent, formInfo, STRUTS_INPUT_PATTERN, "struts");
        }
    }

    /**
     * 指定パターンで入力要素を抽出する。
     */
    private void extractInputsWithPattern(String formContent, FormInfo formInfo,
            Pattern inputPattern, String tagType) {
        Matcher inputMatcher = inputPattern.matcher(formContent);
        while (inputMatcher.find()) {
            String tagName = inputMatcher.group(1);
            String inputAttrs = inputMatcher.group(2);
            Map<String, String> attrs = extractAttributes(inputAttrs);

            // タグ表示名
            String inputTag;
            switch (tagType) {
                case "spring":
                    inputTag = "<form:" + tagName + ">";
                    break;
                case "struts":
                    inputTag = "<html:" + tagName + ">";
                    break;
                default:
                    inputTag = "<" + tagName + ">";
            }

            // パラメータ名（優先順位: path > property > name）
            String parameterName = attrs.get("path");
            if (parameterName == null) {
                parameterName = attrs.get("property");
            }
            if (parameterName == null) {
                parameterName = attrs.get("name");
            }

            // 入力タイプ
            String inputType = attrs.get("type");
            if (inputType == null) {
                // タグ名から推定
                switch (tagName.toLowerCase()) {
                    case "select":
                    case "option":
                    case "options":
                        inputType = "select";
                        break;
                    case "textarea":
                        inputType = "textarea";
                        break;
                    case "checkbox":
                    case "checkboxes":
                        inputType = "checkbox";
                        break;
                    case "radiobutton":
                    case "radiobuttons":
                    case "radio":
                        inputType = "radio";
                        break;
                    case "password":
                        inputType = "password";
                        break;
                    case "hidden":
                        inputType = "hidden";
                        break;
                    case "file":
                        inputType = "file";
                        break;
                    case "submit":
                    case "reset":
                    case "button":
                        inputType = tagName.toLowerCase();
                        break;
                    case "a":
                    case "html:link":
                        inputType = NOT_INPUT_TYPE;
                        break;
                    default:
                        inputType = "text";
                }
            }

            // maxlength
            String maxLength = attrs.get("maxlength");

            // required属性
            boolean required = attrs.containsKey("required");

            // イベント属性を抽出
            Map<String, String> events = new HashMap<>();
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                if (entry.getKey().startsWith("on")) {
                    events.put(entry.getKey(), entry.getValue());
                }
            }

            boolean isButton = "button".equalsIgnoreCase(tagName) || "submit".equalsIgnoreCase(inputType)
                    || "button".equalsIgnoreCase(inputType) || "reset".equalsIgnoreCase(inputType)
                    || "image".equalsIgnoreCase(inputType) || "form:button".equalsIgnoreCase(tagName)
                    || "html:button".equalsIgnoreCase(tagName) || "html:submit".equalsIgnoreCase(tagName)
                    || "html:cancel".equalsIgnoreCase(tagName); // Struts/Spring tags handling
            boolean isLink = "a".equalsIgnoreCase(tagName) || "html:link".equalsIgnoreCase(tagName); // Struts/Spring
                                                                                                     // tags handling
            boolean isButtonOrLink = isButton || isLink;

            // 備考（value, href, innerText）
            List<String> remarkItems = new ArrayList<>();

            // タグ内のテキスト（ボタンorリンクのみ）
            // inputMatcher.group(3) がinnerText
            if (inputMatcher.groupCount() >= 3) {
                String innerText = inputMatcher.group(3);
                if (innerText != null && !innerText.trim().isEmpty() && isButtonOrLink) {
                    remarkItems.add("innerText: " + innerText.trim());
                }
            }

            if (isButton) {
                String value = attrs.get("value");
                if (value != null && !value.isEmpty()) {
                    remarkItems.add("value=" + value);
                }
            }
            if (isLink) {
                String href = attrs.get("href");
                if (href != null && !href.isEmpty()) {
                    remarkItems.add("href=" + href);
                }
            }

            String remarks = String.join("; ", remarkItems);

            // 入力要素を追加（name/path/propertyがある場合、またはボタン・リンク・イベントがある場合）
            if ((parameterName != null && !parameterName.isEmpty()) || isButtonOrLink || !events.isEmpty()) {
                formInfo.inputs
                        .add(new InputElementInfo(inputTag, parameterName, inputType, maxLength, required, events,
                                remarks));
            }
        }
    }

    /**
     * 非入力要素（リンクやページレベルのイベント）を抽出する。
     */
    private void extractNotInputsWithPattern(String originalContent, String maskedContent, String relativePath,
            List<FormInfo> forms) {
        FormInfo pageElements = new FormInfo(relativePath, "", "", "", "(page elements)");

        // 1. Bodyイベントの抽出 (元のコンテンツ)
        Matcher bodyMatcher = BODY_PATTERN.matcher(originalContent);
        if (bodyMatcher.find()) {
            String attrsStr = bodyMatcher.group(1);
            Map<String, String> attrs = extractAttributes(attrsStr);
            Map<String, String> events = new HashMap<>();
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                if (entry.getKey().startsWith("on")) {
                    events.put(entry.getKey(), entry.getValue());
                }
            }

            if (!events.isEmpty()) {
                pageElements.inputs.add(new InputElementInfo("<body>", "", NOT_INPUT_TYPE, "", false, events));
            }
        }

        // 2. リンクの抽出 (元のコンテンツ: フォーム内外すべて)
        extractInputsWithPattern(originalContent, pageElements, LINK_PATTERN, "html");

        // 3. フォーム外の入力要素抽出 (マスクされたコンテンツ: フォーム外のみ)
        extractInputsWithPattern(maskedContent, pageElements, HTML_INPUT_PATTERN, "html");
        extractInputsWithPattern(maskedContent, pageElements, SPRING_INPUT_PATTERN, "spring");
        extractInputsWithPattern(maskedContent, pageElements, STRUTS_INPUT_PATTERN, "struts");

        if (!pageElements.inputs.isEmpty()) {
            forms.add(pageElements);
        }
    }

    /**
     * タグ文字列から属性を抽出する。
     */
    private Map<String, String> extractAttributes(String tagContent) {
        Map<String, String> attrs = new HashMap<>();
        if (tagContent == null) {
            return attrs;
        }
        Matcher matcher = ATTR_PATTERN.matcher(tagContent);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase();
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            attrs.put(name, value);
        }
        return attrs;
    }

    // ==========================================================================
    // マッピングロジック
    // ==========================================================================

    /**
     * スクリプトレットのオブジェクト名をEL式のスコープ名に変換する。
     */
    private String mapObjectToScope(String objectName) {
        switch (objectName) {
            case "request":
                return "requestScope";
            case "session":
                return "sessionScope";
            case "application":
                return "applicationScope";
            case "pageContext":
                return "pageScope";
            default:
                return objectName + "Scope";
        }
    }

    /**
     * スコープ名を日本語に変換する。
     */
    private String toJapaneseScopeName(String scopeName) {
        switch (scopeName) {
            case "requestScope":
                return "requestスコープ";
            case "sessionScope":
                return "sessionスコープ";
            case "applicationScope":
                return "applicationスコープ";
            case "pageScope":
                return "pageスコープ";
            default:
                return scopeName;
        }
    }

    /**
     * 属性とJSPのEL式を突き合わせる。
     */
    private List<ResponseAnalysisResult> matchAttributeToJsp(
            ControllerMethodInfo methodInfo,
            ModelAttribute attr,
            JspAnalysisResult jspResult,
            String viewPath,
            String baseWarning,
            Set<String> handledEls,
            Set<String> handledFormPaths) {

        List<ResponseAnalysisResult> results = new ArrayList<>();

        // この属性に関連するEL式をすべて処理済みとしてマーク
        String prefix1 = "${" + attr.attributeName + ".";
        String prefix2 = "${" + attr.attributeName + "}";
        String prefix3 = "${" + attr.attributeName + "[";

        for (String el : jspResult.elExpressions) {
            String elTrimmed = el.trim();
            if (elTrimmed.startsWith(prefix1) || elTrimmed.equals(prefix2) || elTrimmed.startsWith(prefix3)) {
                handledEls.add(el);
            }
        }

        // この属性に関連するフォーム使用パスをすべて処理済みとしてマーク
        if (jspResult.formUsages.containsKey(attr.attributeName)) {
            Set<String> paths = jspResult.formUsages.get(attr.attributeName);
            for (String path : paths) {
                handledFormPaths.add(attr.attributeName + "." + path);
            }
        }

        // 属性のクラスのフィールドを取得
        List<CtField<?>> fields = getFieldsForType(attr.typeReference);

        if (fields.isEmpty()) {
            // フィールドが取得できない場合
            results.add(new ResponseAnalysisResult(
                    methodInfo.controllerName,
                    methodInfo.methodName,
                    viewPath,
                    attr.attributeName,
                    "",
                    attr.javaClassName,
                    "",
                    "",
                    attr.origin,
                    baseWarning.isEmpty() ? "クラス定義が見つかりません" : baseWarning + "; クラス定義が見つかりません"));
            return results;
        }

        // EL式マッチング用のパターン（属性名.フィールド名で始まる、または属性名.フィールド名.xxxのパターン）
        // ${attr.field} または ${attr.field.xxx} にマッチ
        for (CtField<?> field : fields) {
            String fieldName = field.getSimpleName();
            String fieldPattern = attr.attributeName + "." + fieldName;

            // EL式の検索（完全一致パターン：${attr.field} または ${attr.field.xxx}）
            String matchedEl = null;
            for (String el : jspResult.elExpressions) {
                // EL式の中身を取得 (${...}の...部分)
                String elContent = el.substring(2, el.length() - 1).trim();
                // 完全一致 または ネストアクセス（.で続く）
                if (elContent.equals(fieldPattern) || elContent.startsWith(fieldPattern + ".") ||
                        elContent.startsWith(fieldPattern + "[")) {
                    matchedEl = el;
                    handledEls.add(el); // マッチしたEL式を記録
                    break;
                }
            }

            // フォーム使用状況のチェック
            boolean usedInForm = false;
            String formRef = null;
            if (jspResult.formUsages.containsKey(attr.attributeName)) {
                Set<String> formPaths = jspResult.formUsages.get(attr.attributeName);
                for (String path : formPaths) {
                    if (path.equals(fieldName) || path.startsWith(fieldName + ".")
                            || path.startsWith(fieldName + "[")) {
                        usedInForm = true;
                        formRef = "Form: path=\"" + path + "\"";
                        break;
                    }
                }
            }

            String usageStatus = (matchedEl != null || usedInForm) ? USED : UNUSED;
            String reference = matchedEl != null ? matchedEl : (formRef != null ? formRef : "");

            results.add(new ResponseAnalysisResult(
                    methodInfo.controllerName,
                    methodInfo.methodName,
                    viewPath,
                    attr.attributeName,
                    reference,
                    attr.javaClassName,
                    fieldName,
                    usageStatus,
                    attr.origin,
                    baseWarning));
        }

        return results;
    }

    /**
     * 型からフィールド一覧を取得する。
     */
    private List<CtField<?>> getFieldsForType(CtTypeReference<?> typeRef) {
        List<CtField<?>> fields = new ArrayList<>();

        if (typeRef == null) {
            return fields;
        }

        CtType<?> type = typeRef.getTypeDeclaration();
        if (type == null) {
            return fields;
        }

        // このクラスと親クラスのフィールドを収集（親が先に来るようにLinkedHashSetを使用）
        Set<CtType<?>> typesToAnalyze = new LinkedHashSet<>();
        collectTypeHierarchy(type, typesToAnalyze);

        for (CtType<?> t : typesToAnalyze) {
            for (CtField<?> field : t.getFields()) {
                // staticフィールドは除外
                if (!field.isStatic()) {
                    fields.add(field);
                }
            }
        }

        return fields;
    }

    // ==========================================================================
    // Excel出力ロジック
    // ==========================================================================

    /**
     * Excelファイルを生成する。
     */
    private void generateExcel(List<ResponseAnalysisResult> results, List<FormInfo> formResults,
            String outputFile) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle jspHeaderStyle = createJspHeaderStyle(workbook);
            CellStyle dataStyle = createDataStyle(workbook);
            CellStyle usedStyle = createUsedStyle(workbook);
            CellStyle unusedStyle = createUnusedStyle(workbook);

            // ResponseAnalysisシートを生成
            generateResponseAnalysisSheet(workbook, results, headerStyle, jspHeaderStyle, dataStyle, usedStyle,
                    unusedStyle);

            // RequestAnalysisシートを生成
            generateRequestAnalysisSheet(workbook, formResults, headerStyle, jspHeaderStyle, dataStyle);

            // ファイルに書き出し
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                workbook.write(fos);
            }
        }
    }

    /**
     * ResponseAnalysisシートを生成する。
     */
    private void generateResponseAnalysisSheet(Workbook workbook, List<ResponseAnalysisResult> results,
            CellStyle headerStyle, CellStyle jspHeaderStyle, CellStyle dataStyle, CellStyle usedStyle,
            CellStyle unusedStyle) {
        Sheet sheet = workbook.createSheet(SHEET_NAME);

        // ヘッダー
        String[] headers = {
                "Controller", "Method", "View Path", "Attribute Name",
                "JSP Reference", "モデルクラス", "モデルクラスのフィールド",
                "使用状況", "属性の由来", "警告/備考"
        };

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            // 特定の項目は別のスタイルを適用 (View Path, Attribute Name, JSP Reference)
            if (i >= 2 && i <= 4) {
                cell.setCellStyle(jspHeaderStyle);
            } else {
                cell.setCellStyle(headerStyle);
            }
        }

        // データ行
        int rowNum = 1;
        for (ResponseAnalysisResult result : results) {
            Row row = sheet.createRow(rowNum++);

            createCell(row, 0, result.controller, dataStyle);
            createCell(row, 1, result.method, dataStyle);
            createCell(row, 2, result.viewPath, dataStyle);
            createCell(row, 3, result.attributeName, dataStyle);
            createCell(row, 4, result.jspReference, dataStyle);
            createCell(row, 5, result.javaClass, dataStyle);
            createCell(row, 6, result.javaField, dataStyle);

            // 使用状況セル（値のみ設定、色分けは条件付き書式で行う）
            createCell(row, 7, result.usageStatus, dataStyle);

            createCell(row, 8, result.attributeOrigin, dataStyle);
            createCell(row, 9, result.warning, dataStyle);
        }

        // 列幅を自動調整
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = sheet.getColumnWidth(i);
            if (currentWidth < 3000) {
                sheet.setColumnWidth(i, 3000);
            }
        }

        // 条件付き書式を設定（使用状況列: インデックス7）
        if (rowNum > 1) {
            SheetConditionalFormatting sheetCF = sheet.getSheetConditionalFormatting();

            // USEDの色分け (緑)
            ConditionalFormattingRule ruleUsed = sheetCF.createConditionalFormattingRule("$H2=\"" + USED + "\"");
            PatternFormatting fillUsed = ruleUsed.createPatternFormatting();
            fillUsed.setFillBackgroundColor(IndexedColors.LIGHT_GREEN.getIndex());
            fillUsed.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

            // UNUSEDの色分け (グレイ)
            ConditionalFormattingRule ruleUnused = sheetCF.createConditionalFormattingRule("$H2=\"" + UNUSED + "\"");
            PatternFormatting fillUnused = ruleUnused.createPatternFormatting();
            fillUnused.setFillBackgroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            fillUnused.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

            // モデルクラスが設定されていない行の色分け
            ConditionalFormattingRule ruleUnset = sheetCF.createConditionalFormattingRule("AND($H2=\"\", $F2=\"\")");
            PatternFormatting fillUnset = ruleUnset.createPatternFormatting();
            fillUnset.setFillBackgroundColor(IndexedColors.LIGHT_ORANGE.getIndex());
            fillUnset.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

            CellRangeAddress[] regions = {
                    CellRangeAddress.valueOf("D2:H" + rowNum)
            };

            sheetCF.addConditionalFormatting(regions,
                    new ConditionalFormattingRule[] { ruleUsed, ruleUnused, ruleUnset });

            // グルーピング用の太線 (MEDIUM top border)
            // 列 A, B, C, D の値が変化した際に行の頭に線を引く
            String[] commonCols = { "$A", "$B", "$C", "$D" };
            for (String col : commonCols) {
                ConditionalFormattingRule groupRule = sheetCF.createConditionalFormattingRule(
                        "AND(" + col + "2<>OFFSET(" + col + "2, -1, 0))");
                BorderFormatting border = groupRule.createBorderFormatting();
                border.setBorderTop(BorderStyle.MEDIUM);

                CellRangeAddress[] groupRegions = {
                        CellRangeAddress.valueOf(col.substring(1) + "2:J" + rowNum)
                };
                sheetCF.addConditionalFormatting(groupRegions, groupRule);
            }
        }

        // オートフィルターを設定
        sheet.setAutoFilter(new CellRangeAddress(0, rowNum - 1, 0, headers.length - 1));
    }

    /**
     * RequestAnalysisシートを生成する。
     */
    private void generateRequestAnalysisSheet(Workbook workbook, List<FormInfo> formResults,
            CellStyle headerStyle, CellStyle jspHeaderStyle, CellStyle dataStyle) {
        Sheet sheet = workbook.createSheet(REQUEST_SHEET_NAME);

        // ヘッダー
        String[] headers = {
                "JSPファイルパス", "Form Action", "Form Method", "Root Model",
                "Input Tag", "Parameter Name", "Input Type", "Max Length",
                "Required", "イベント", "備考", "JSONキー", "JSONキー(ネスト)"
        };

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);

            // 特定の項目は別のスタイルを適用 (JSONキー, JSONキー(ネスト))
            if (i <= 10) {
                cell.setCellStyle(jspHeaderStyle);
            } else {
                cell.setCellStyle(headerStyle);
            }
        }

        // データ行
        int rowNum = 1;
        for (FormInfo form : formResults) {
            if (form.inputs.isEmpty()) {
                // 入力要素がない場合（フォーム自体がない、またはフォーム内に入力要素がない）
                Row row = sheet.createRow(rowNum++);
                int col = 0;
                createCell(row, col++, form.jspFilePath, dataStyle);
                createCell(row, col++, form.action, dataStyle);
                createCell(row, col++, form.method, dataStyle);
                createCell(row, col++, form.rootModel, dataStyle);
                createCell(row, col++, form.formTag, dataStyle); // Input Tag列にフォームタグ情報を表示（または空にする）
                // 以降の列は空
                createCell(row, col++, "", dataStyle);
                createCell(row, col++, "", dataStyle);
                createCell(row, col++, "", dataStyle);
                createCell(row, col++, "", dataStyle);
                createCell(row, col++, "", dataStyle);
                createCell(row, col++, "", dataStyle);
                createCell(row, col++, "", dataStyle);
                createCell(row, col++, "", dataStyle);
            } else {
                for (InputElementInfo input : form.inputs) {
                    Row row = sheet.createRow(rowNum++);
                    int col = 0;
                    createCell(row, col++, form.jspFilePath, dataStyle);
                    createCell(row, col++, form.action, dataStyle);
                    createCell(row, col++, form.method, dataStyle);
                    createCell(row, col++, form.rootModel, dataStyle);
                    createCell(row, col++, input.inputTag, dataStyle);
                    createCell(row, col++, input.parameterName, dataStyle);
                    createCell(row, col++, input.inputType, dataStyle);
                    createCell(row, col++, input.maxLength, dataStyle);
                    createCell(row, col++, input.required ? "true" : "", dataStyle);
                    createCell(row, col++, input.events, dataStyle);
                    createCell(row, col++, input.remarks, dataStyle);
                    createCell(row, col++, input.jsonKeyEstimate, dataStyle);
                    createCell(row, col++,
                            input.nestPath + (input.nestPath.isEmpty() ? "" : ".") + input.jsonKeyEstimate,
                            dataStyle);
                }
            }
        }

        // 列幅を自動調整
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            int currentWidth = sheet.getColumnWidth(i);
            if (currentWidth < 3000) {
                sheet.setColumnWidth(i, 3000);
            }
        }

        // オートフィルターを設定
        if (rowNum > 1) {
            sheet.setAutoFilter(new CellRangeAddress(
                    0, rowNum - 1, 0, headers.length - 1));

            // 条件付き書式の設定
            SheetConditionalFormatting sheetCF = sheet.getSheetConditionalFormatting();

            // 1. JSPファイルパスが変わったら区切り線 (A列〜M列)
            // AND($A2<>OFFSET($A2, -1, 0))
            ConditionalFormattingRule ruleJspChange = sheetCF.createConditionalFormattingRule(
                    "AND($A2<>OFFSET($A2, -1, 0))");
            BorderFormatting borderJsp = ruleJspChange.createBorderFormatting();
            borderJsp.setBorderTop(BorderStyle.MEDIUM);

            CellRangeAddress[] regionAll = {
                    CellRangeAddress.valueOf("A2:M" + (rowNum - 1))
            };
            sheetCF.addConditionalFormatting(regionAll, ruleJspChange);

            // 2. Form Actionが変わったら区切り線 (B列〜M列) ※JSPが同じ場合
            // AND($A2=OFFSET($A2, -1, 0), $B2<>OFFSET($B2, -1, 0))
            ConditionalFormattingRule ruleActionChange = sheetCF.createConditionalFormattingRule(
                    "AND($A2=OFFSET($A2, -1, 0), $B2<>OFFSET($B2, -1, 0))");
            BorderFormatting borderAction = ruleActionChange.createBorderFormatting();
            borderAction.setBorderTop(BorderStyle.MEDIUM);

            CellRangeAddress[] regionForm = {
                    CellRangeAddress.valueOf("B2:M" + (rowNum - 1))
            };
            sheetCF.addConditionalFormatting(regionForm, ruleActionChange);

            // 3. Input Typeが空の場合、背景色をグレー (B列〜M列)
            // AND($G2="")
            ConditionalFormattingRule ruleNoInput = sheetCF.createConditionalFormattingRule("AND($G2=\"\")");
            PatternFormatting fillNoInput = ruleNoInput.createPatternFormatting();
            fillNoInput.setFillBackgroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            fillNoInput.setFillPattern(PatternFormatting.SOLID_FOREGROUND);

            sheetCF.addConditionalFormatting(regionForm, ruleNoInput);
        }
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
     * ヘッダー用スタイルを作成する。
     */
    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        style.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);

        style.setAlignment(HorizontalAlignment.CENTER);

        style.setBorderTop(BorderStyle.DOTTED);
        style.setBorderBottom(BorderStyle.DOTTED);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * JSP関連ヘッダー用スタイルを作成する。
     */
    private CellStyle createJspHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        style.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);

        style.setAlignment(HorizontalAlignment.CENTER);

        style.setBorderTop(BorderStyle.DOTTED);
        style.setBorderBottom(BorderStyle.DOTTED);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * データ用スタイルを作成する。
     */
    private CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        style.setBorderTop(BorderStyle.DOTTED);
        style.setBorderBottom(BorderStyle.DOTTED);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * USED用スタイルを作成する（緑色背景）。
     */
    private CellStyle createUsedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        style.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        style.setAlignment(HorizontalAlignment.CENTER);

        style.setBorderTop(BorderStyle.DOTTED);
        style.setBorderBottom(BorderStyle.DOTTED);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }

    /**
     * UNUSED用スタイルを作成する（赤色背景）。
     */
    private CellStyle createUnusedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();

        style.setFillForegroundColor(IndexedColors.CORAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        style.setAlignment(HorizontalAlignment.CENTER);

        style.setBorderTop(BorderStyle.DOTTED);
        style.setBorderBottom(BorderStyle.DOTTED);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        return style;
    }
}
