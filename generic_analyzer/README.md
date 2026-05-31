# Generic Java Analyzer

Spoonライブラリを使用したJava静的解析CLIツールです。  
レガシーコードのマイグレーション調査などに活用できます。

## 機能

1. **型使用の調査 (TypeUsageAnalyzer)**
   - 正規表現で指定されたパッケージやクラスを使用している箇所を特定
   - 検出対象: 変数宣言、継承(extends/implements)、throws/catch句、キャスト、ジェネリクス、import文、Javadoc (@exception, @throws, @see, @link)、**コンストラクタ呼び出し**、**メソッド呼び出し**

2. **メソッド・フィールド使用の調査 (MethodOrFieldUsageAnalyzer)**
   - 特定の名前のメソッド（コンストラクタを含む）やフィールドが使用されている箇所を特定
   - SimpleName (`executeQuery`) または QualifiedName (`java.sql.Statement.executeQuery`) で指定可能

3. **アノテーションの調査 (AnnotationAnalyzer)**
   - 特定のアノテーションが付与されているクラス、メソッド、フィールド、引数を特定
   - **親クラスやインターフェースから継承しているアノテーションも再帰的に検出可能**

4. **文字列リテラルの調査 (StringLiteralAnalyzer)**
   - 正規表現で指定された文字列リテラルが含まれている箇所を特定

5. **戻り値比較の追跡 (ReturnValueComparisonAnalyzer)**
   - 指定メソッドの戻り値を格納した変数を追跡し、`equals()` や `switch` で比較されている値を抽出
   - `var.equals("literal")` / `"literal".equals(var)`（逆パターン）に対応
   - 定数比較の場合、定義元の文字列リテラルまで解決
   - `if-else` / `else-if` チェーン、`switch` 文（`default` 有無）に対応

6. **ローカル変数追跡 (LocalVariableTrackingAnalyzer)**
   - 指定した変数名のローカル変数を追跡し、その変数に設定されている値と、設定ルートの分岐条件を抽出
   - 設定値が定数の場合は定義元の文字列リテラルまで追跡して解決
   - `if`, `switch`, `for`, `while` などの制御構文から設定ルートの条件式を抽出
   - CSV出力時には「変数名」「設定値」「設定ルート」のカラムが追加で出力されます

7. **呼び出しルート追跡 (CallTrackingAnalyzer)**
   - 正規表現で指定したクラスやメソッドの呼び出し（コンストラクタ呼び出し含む）を基点として、呼び出し元のメソッドを再帰的に遡って特定
   - 各呼び出し経路・層における分岐条件(`if`, `switch`, `for`等)を抽出して構造出力
   - CSV出力時には「呼び出しルートと分岐条件」および「到達エントリーポイント」のカラムが追加で出力されます

8. **定数抽出 (ConstantExtractionAnalyzer)**
   - 正規表現で指定したクラスや定数名に合致する「定数 (`static final` フィールド)」を抽出し、その代入値（リテラル）を出力
   - 文字列のダブルクォートなどは自動で除去して実際の値を出力
   - Javadocコメント（`/** ... */`）が記述されている場合はそれも抽出
   - CSV出力時には「定数名」、「値」、および「javadoc」のカラムが追加で分割出力されます

9. **循環参照検出 (CircularDependencyAnalyzer)**
   - クラス間のコンストラクタ、フィールド、セッターメソッド等の依存関係を静的に走査し、DI（依存性の注入）における依存関係グラフを構築します
   - グラフ内を探索して循環参照サイクル（閉路）を検出し、そのサイクルパス（例: `A -> B -> C -> A`）を出力します
   - レガシーシステム等も考慮し、`@Autowired` などの特定アノテーションの有無に関わらず関連し合うインジェクションポイント（特に `@Lazy` アノテーションによる解消候補となるセッター等）をすべて抽出・報告します

10. **完全修飾名 (フルパス) の参照検出 (FullyQualifiedNameUsageAnalyzer)**
    - import文を使用せず、ソースコード内で直接クラスの完全修飾名（例: `java.util.List`）を指定して参照している箇所を検出します
    - 変数宣言やキャスト、`instanceof`、`new`、引数・戻り値など様々な記述に対応しています

11. **クラス間依存関係の抽出・図式化 (ClassDependencyAnalyzer)**
    - クラス間の依存関係（継承、インターフェース実装、フィールド保持）を静的に解析し、クラスレベルの依存関係グラフを生成します
    - 出力フォーマットとして **Mermaid** (`-f mermaid`) と **PlantUML** (`-f plantuml`) に対応しており、VS Code拡張機能等で直接描画・プレビューできます
    - `--exclude-dependency` オプションで除外するパッケージ/クラスを正規表現で指定可能（例: `com\.example\..*` で指定パッケージ以下を除外）
    - `txt` / `csv` 形式でも依存関係の一覧を従来フォーマットで確認できます

12. **文字列結合の調査 (StringConcatAnalyzer)**
    - 文字列の `+` 演算子による結合および `StringBuilder`/`StringBuffer` の `append` において、`null` 値が暗黙的に `"null"` 文字列として結合されてしまう危険な箇所を特定
    - プリミティブ型変数、定数、例外構築メッセージ（`throw`文や例外クラスのコンストラクタ引数）、例外オブジェクト自身（例: `e` の結合）や例外メッセージ取得メソッド（例: `e.getMessage()` など）、および `null` 安全な三項演算子は自動的に除外
    - `--exclude-partial-constants` オプションを併用することで、結合部分に定数（リテラルや static final 定数）が1つでも含まれる結合箇所を除外することが可能

13. **非推奨要素(削除予定)の調査 (DeprecatedForRemovalAnalyzer)**
    - `@Deprecated(forRemoval=true)` が付与されたクラス、インターフェース、メソッド、コンストラクタ、フィールドを使用（参照）している箇所を特定

## 必要環境

- Java 17以上
- Gradle 7.x以上

## ビルド

```bash
# Fat JAR (Shadow JAR) 生成
./gradlew shadowJar
# 生成物: generic-analyzer.jar (プロジェクト直下)
```

## 使用方法

### ヘルプ表示

```bash
java -jar generic-analyzer.jar -h
```

### 基本コマンド

```bash
java -jar generic-analyzer.jar -s <ソースディレクトリ> [解析オプション]
```

### オプション一覧

#### 共通・環境オプション
| オプション | 説明 |
|-----------|------|
| `-s, --source <dir,...>` | 解析対象のソースディレクトリ (カンマ区切りで複数指定可) |
| `-cp, --classpath <path,...>` | クラスパス/JAR (カンマ区切りで複数指定可) |
| `-cl, --compliance <level>` | Javaコンプライアンスレベル (デフォルト: 21) |
| `-e, --encoding <enc>` | ソースエンコーディング (デフォルト: UTF-8) |
| `-o, --output <file>` | 出力ファイル名 (省略時は標準出力) |
| `-f, --format <format>` | 出力フォーマット: txt, csv, mermaid, plantuml (デフォルト: txt) |
| `--output-csv-encoding <enc>` | CSV出力のエンコーディング (デフォルト: windows-31j) |

#### 個別解析オプション
| オプション | 説明 |
|-----------|------|
| `-t, --type-pattern <regex>` | 型使用パターン (正規表現) |
| `-l, --literal-pattern <regex>` | 文字列リテラルパターン (正規表現) |
| `-n, --names <name,...>` | メソッド/フィールド名 (カンマ区切り) |
| `-a, --annotations <ann,...>` | アノテーション名（カンマ区切り、引数を含む正規表現指定も可能） |
| `-tr, --track-return <method,...>` | 戻り値追跡対象メソッド名 (カンマ区切り) |
| `-tl, --track-local-var <var,...>` | ローカル変数追跡対象変数名 (カンマ区切り) |
| `-tc, --track-call <regex>` | 呼び出しルート追跡対象パターン (正規表現) |
| `--extract-constant <classRegex>:<fieldRegex>` | 定数抽出対象パターン (クラス名と定数名をコロンで区切る) |
| `-cd, --circular-dependency` | セッターインジェクション等による循環参照をチェックして抽出するフラグ |
| `-fq, --fully-qualified` | フルパスでのクラス参照（完全修飾名指定）箇所を検出するフラグ |
| `-cld, --class-dependency` | クラス間の依存関係（継承、実装、フィールド）を抽出するフラグ |
| `--exclude-dependency <regex>` | 依存関係図から除外するパッケージ/クラスパターン (正規表現) |
| `-sc, --string-concat` | 文字列結合（+演算子やStringBuilder）をチェックして抽出するフラグ |
| `--exclude-partial-constants` | どちらか一方が定数の文字列結合箇所を除外するフラグ（-sc併用時のみ有効） |
| `-dfr, --deprecated-for-removal` | `@Deprecated(forRemoval=true)` が付与された要素の使用箇所を抽出するフラグ |



### 使用例

#### java.sqlパッケージの使用箇所を検索

```bash
java -jar generic-analyzer.jar -s src/main/java -t 'java\.sql\..*'
```

#### SQL文を含む文字列リテラルを検索

```bash
java -jar generic-analyzer.jar -s src -l 'SELECT.*FROM'
```

#### @Deprecatedアノテーションの使用箇所を検索

```bash
java -jar generic-analyzer.jar -s src -a Deprecated
```

#### 引数を含めたアノテーションの正規表現検索（例: @SuppressWarnings("all") のみ検出）

```bash
java -jar generic-analyzer.jar -s src -a "SuppressWarnings\(.*all.*\)"
```

#### 引数を含めたアノテーションの正規表現検索（例: @Deprecated(forRemoval=true) のみ検出）

```bash
java -jar generic-analyzer.jar -s src -a "Deprecated\(forRemoval\s*=\s*true\)"
```

#### 特定のメソッド呼び出しを検索

```bash
java -jar generic-analyzer.jar -s src -n executeQuery,executeUpdate
```

#### 複数の解析を同時実行

```bash
java -jar generic-analyzer.jar -s src -t 'java\.sql\..*' -n executeQuery -l 'SELECT'
```

#### メソッド戻り値の比較値を追跡

```bash
java -jar generic-analyzer.jar -s src -tr getStatus,getRole
```

#### ローカル変数の設定値と設定ルートを追跡

```bash
java -jar generic-analyzer.jar -s src -tl status,role,result -f csv -o local_variables.csv
```

#### 呼び出しルートと分岐条件を追跡

```bash
java -jar generic-analyzer.jar -s src -tc 'executeQuery' -f csv -o call_routes.csv
```

#### 定数の抽出とCSV出力（クラス・フィールド指定）

```bash
java -jar generic-analyzer.jar -s src --extract-constant '.*Constants.*:MAX_.*' -f csv -o constants.csv
```

#### 循環参照の検出

```bash
java -jar generic-analyzer.jar -s src -cd
```

#### 完全修飾名 (フルパス) の検出

```bash
java -jar generic-analyzer.jar -s src -fq
```

#### クラス間依存関係の抽出（Mermaid形式）

```bash
java -jar generic-analyzer.jar -s src -cld -f mermaid -o dependency.md
```

#### クラス間依存関係の抽出（PlantUML形式、標準APIを除外）

```bash
java -jar generic-analyzer.jar -s src -cld --exclude-dependency 'com\.example\..*' -f plantuml -o dependency.puml
```

#### CSV形式でファイル出力

```bash
java -jar generic-analyzer.jar -s src -a Deprecated -f csv -o result.csv
```

#### 危険な null 文字列結合箇所を検索

```bash
java -jar generic-analyzer.jar -s src -sc
```

#### 危険な null 文字列結合箇所を検索（部分的な定数結合を除外）

```bash
java -jar generic-analyzer.jar -s src -sc --exclude-partial-constants
```

#### @Deprecated(forRemoval=true) が付与された要素の使用箇所を検索

```bash
java -jar generic-analyzer.jar -s src -dfr
```

## 出力形式

検出結果には以下の情報が含まれます：

- **検出カテゴリ**: Type Usage, Method Call, Constructor Call, Field Access, Annotation, String Literal, Return Value Comparison, Local Variable Tracking, Call Route Tracking, 定数定義, Circular Reference, String Concatenation, Deprecated for Removal

- **ファイル名と行番号**: 検出箇所のファイル名（解析対象ソースディレクトリからの相対パス）と行番号
- **スコープ**: どのクラスのどのメソッド内で検出されたか
- **コードスニペット**: 検出した要素を含む親のステートメント全体
- **検出内容 (matchedElement)**: 循環参照が検出された場合など、具体的なサイクル経路 (`Cycle: ClassA -> ClassB -> ClassA`) や要素情報が含まれます

### 出力例

```
════════════════════════════════════════════════════════════════════════════════
検出結果
════════════════════════════════════════════════════════════════════════════════
────────────────────────────────────────────────────────────────────────────────
【Type Usage】 java.sql.Connection
  File: com/example/dao/UserDao.java : 25
  Scope: com.example.dao.UserDao#findById(Long)
  Code: Connection conn = dataSource.getConnection()
────────────────────────────────────────────────────────────────────────────────
【Method Call】 java.sql.Connection.prepareStatement()
  File: com/example/dao/UserDao.java : 27
  Scope: com.example.dao.UserDao#findById(Long)
  Code: PreparedStatement ps = conn.prepareStatement(sql)

════════════════════════════════════════════════════════════════════════════════
解析完了
════════════════════════════════════════════════════════════════════════════════
解析ファイル数: 15
スキャン要素数: 2847
検出件数: 23
════════════════════════════════════════════════════════════════════════════════
```

## アーキテクチャ

```
analyzer/
├── Main.java                         # CLIエントリーポイント
├── Analyzer.java                     # 解析インターフェース
├── AnalysisContext.java              # 解析コンテキスト（設定・結果）
├── AnalysisResult.java               # 検出結果データクラス
├── AnalysisOrchestrator.java         # CtScannerオーケストレータ
├── impl/
│   ├── TypeUsageAnalyzer.java        # 型使用調査
│   ├── MethodOrFieldUsageAnalyzer.java # メソッド/フィールド調査
│   ├── AnnotationAnalyzer.java       # アノテーション調査
│   ├── StringLiteralAnalyzer.java    # 文字列リテラル調査
│   ├── ReturnValueComparisonAnalyzer.java # 戻り値比較追跡
│   ├── LocalVariableTrackingAnalyzer.java # ローカル変数追跡
│   ├── LocalVariableTrackingResult.java   # ローカル変数追跡出力フォーマット
│   ├── CallTrackingAnalyzer.java          # 呼び出しルート追跡
│   ├── CallTrackingResult.java            # 呼び出しルート追跡出力フォーマット
│   ├── ConstantExtractionAnalyzer.java    # 定数抽出
│   ├── ConstantExtractionResult.java      # 定数抽出出力フォーマット
│   ├── CircularDependencyAnalyzer.java    # 循環参照解析
│   ├── FullyQualifiedNameUsageAnalyzer.java # フルパス（完全修飾名）参照検出
│   ├── ClassDependencyAnalyzer.java       # クラス間依存関係抽出
│   └── StringConcatAnalyzer.java          # 文字列結合調査

```

## ライセンス

MIT License
