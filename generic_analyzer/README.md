# Generic Java Analyzer

Spoonライブラリを使用したJava静的解析CLIツールです。  
レガシーコードのマイグレーション調査などに活用できます。

## 機能

1. **型使用の調査 (TypeUsageAnalyzer)**
   - 正規表現で指定されたパッケージやクラスを使用している箇所を特定

2. **メソッド・フィールド使用の調査 (MethodOrFieldUsageAnalyzer)**
   - 特定の名前のメソッド（コンストラクタを含む）やフィールドが使用されている箇所を特定

3. **アノテーションの調査 (AnnotationAnalyzer)**
   - 特定のアノテーションが付与されているクラス、メソッド、フィールド、引数を特定

4. **文字列リテラルの調査 (StringLiteralAnalyzer)**
   - 正規表現で指定された文字列リテラルが含まれている箇所を特定

## 必要環境

- Java 17以上
- Gradle 7.x以上

## ビルド

```bash
./gradlew build
```

## 使用方法

### ヘルプ表示

```bash
./gradlew run --args="-h"
```

### 基本コマンド

```bash
./gradlew run --args="-s <ソースディレクトリ> [解析オプション]"
```

### オプション一覧

| オプション | 説明 |
|-----------|------|
| `-s, --source <dir,...>` | 解析対象のソースディレクトリ (カンマ区切りで複数指定可) |
| `-cp, --classpath <path,...>` | クラスパス/JAR (カンマ区切りで複数指定可) |
| `-cl, --compliance <level>` | Javaコンプライアンスレベル (デフォルト: 21) |
| `-e, --encoding <enc>` | ソースエンコーディング (デフォルト: UTF-8) |
| `-t, --type-pattern <regex>` | 型使用パターン (正規表現) |
| `-l, --literal-pattern <regex>` | 文字列リテラルパターン (正規表現) |
| `-n, --names <name,...>` | メソッド/フィールド名 (カンマ区切り) |
| `-a, --annotations <ann,...>` | アノテーション名 (カンマ区切り) |

### 使用例

#### java.sqlパッケージの使用箇所を検索

```bash
./gradlew run --args="-s src/main/java -t 'java\\.sql\\..*'"
```

#### SQL文を含む文字列リテラルを検索

```bash
./gradlew run --args="-s src -l 'SELECT.*FROM'"
```

#### @Deprecatedアノテーションの使用箇所を検索

```bash
./gradlew run --args="-s src -a Deprecated"
```

#### 特定のメソッド呼び出しを検索

```bash
./gradlew run --args="-s src -n executeQuery,executeUpdate"
```

#### 複数の解析を同時実行

```bash
./gradlew run --args="-s src -t 'java\\.sql\\..*' -n executeQuery -l 'SELECT'"
```

## 出力形式

検出結果には以下の情報が含まれます：

- **検出カテゴリ**: Type Usage, Method Call, Constructor Call, Field Access, Annotation, String Literal
- **ファイル名と行番号**: 検出箇所のファイル名と行番号
- **スコープ**: どのクラスのどのメソッド内で検出されたか
- **コードスニペット**: 検出した要素を含む親のステートメント全体

### 出力例

```
════════════════════════════════════════════════════════════════════════════════
検出結果
════════════════════════════════════════════════════════════════════════════════
────────────────────────────────────────────────────────────────────────────────
【Type Usage】 java.sql.Connection
  File: UserDao.java : 25
  Scope: com.example.dao.UserDao # findById(Long)
  Code: Connection conn = dataSource.getConnection()
────────────────────────────────────────────────────────────────────────────────
【Method Call】 java.sql.Connection.prepareStatement()
  File: UserDao.java : 27
  Scope: com.example.dao.UserDao # findById(Long)
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
└── impl/
    ├── TypeUsageAnalyzer.java        # 型使用調査
    ├── MethodOrFieldUsageAnalyzer.java # メソッド/フィールド調査
    ├── AnnotationAnalyzer.java       # アノテーション調査
    └── StringLiteralAnalyzer.java    # 文字列リテラル調査
```

## ライセンス

MIT License
