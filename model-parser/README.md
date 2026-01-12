# Model Parser CLI

JavaモデルクラスをパースしてAPI仕様書の元ネタとなるExcelファイルを生成するCLIツールです。

Spring MVCなどのレガシーシステムのモデルクラス（DTOなど）を解析し、フィールド情報をExcelファイルに出力します。

## 特徴

- **Spoonによるソース解析**: 依存ライブラリがなくても解析可能（NoClasspathモード）
- **Javadoc対応**: フィールドのJavadocから論理名と説明を自動抽出
- **型変換**: Java型をJSON型に自動マッピング
- **バリデーションアノテーション認識**: `@NotNull`等のアノテーションから必須項目を判定

## 必要条件

- Java 17以上
- Gradle 7.x以上

## ビルド方法

```bash
# 通常のビルド
./gradlew build

# FatJar（依存関係を含む単体実行可能JAR）の生成
./gradlew shadowJar
```

ビルド成果物は `build/libs/model-parser.jar` に生成されます。

## 使用方法

```bash
java -jar model-parser.jar [options]
```

### オプション

| オプション | 説明 | 必須 | デフォルト |
| :--- | :--- | :--- | :--- |
| `-s <dirs>` | ソースディレクトリ（カンマ区切りで複数指定可） | ○ | - |
| `-cp <paths>` | クラスパスエントリ（カンマ区切りで複数指定可） | - | - |
| `-cl <level>` | Javaコンプライアンスレベル | - | 21 |
| `-e <encoding>` | ソースコードのエンコーディング | - | UTF-8 |
| `-t <file>` | 対象クラス一覧ファイル | ○ | - |
| `-o <file>` | 出力Excelファイル名 | - | output.xlsx |
| `-h, --help` | ヘルプメッセージを表示 | - | - |

### クラスパス（-cp）オプション

- JARファイルを直接指定できます
- ディレクトリを指定した場合は、そのディレクトリと直下のJARファイルがクラスパスに追加されます

### 対象クラス一覧ファイル（-t）

対象クラスを1行1クラスで記載したテキストファイルを指定します。
完全修飾クラス名または単純クラス名で指定できます。

```text
# コメント行（#で始まる行は無視されます）
com.example.dto.UserDto
com.example.dto.AddressDto
OrderResponse
```

## 使用例

### 基本的な使用方法

```bash
java -jar model-parser.jar \
  -s src/main/java \
  -t classes.txt \
  -o api-spec.xlsx
```

### 依存ライブラリを含む場合

```bash
java -jar model-parser.jar \
  -s src/main/java \
  -cp lib,external-libs/validation-api.jar \
  -t classes.txt \
  -o api-spec.xlsx
```

### 複数のソースディレクトリを指定

```bash
java -jar model-parser.jar \
  -s src/main/java,src/generated/java \
  -cl 17 \
  -e Shift_JIS \
  -t classes.txt \
  -o api-spec.xlsx
```

## 出力フォーマット

Excelファイルに「ResourceDef」シートが作成され、以下のカラムが出力されます。

| 列 | ヘッダー名 | 説明 |
| :--- | :--- | :--- |
| A | JSONキー | フィールド名（JSONのキーとして使用） |
| B | 論理名 | Javadocの1行目 |
| C | 説明 | Javadocの2行目以降 |
| D | JSON型 | string, number, boolean, array, object |
| E | 内部要素/参照 | 配列やオブジェクトの内部型 |
| F | 必須 | 〇 または - |
| G | サンプル値 | 型に応じた自動生成値 |
| H | Javaクラス名 | クラスのSimpleName |
| I | Javaフィールド名 | フィールド変数名 |
| J | Java型 | Javaの型名（例: List<String>） |

## 型変換ルール

| Javaの型 | JSON型 | 備考 |
| :--- | :--- | :--- |
| String, Date, LocalDateTime等 | string | 文字列系 |
| int, Integer, BigDecimal等 | number | 数値系 |
| boolean, Boolean | boolean | 真偽値 |
| List<T>, Set<T>, T[] | array | コレクション・配列 |
| その他のクラス | object | DTO等 |

## 必須判定

以下のアノテーションがフィールドに付与されている場合、「必須」列に「〇」が出力されます。

- `@NotNull` (javax.validation / jakarta.validation)
- `@NonNull` (Spring / Lombok)
- `@Nonnull` (JSR-305)
- `@NotEmpty` (javax.validation / jakarta.validation)
- `@NotBlank` (javax.validation / jakarta.validation)

## ライセンス

MIT License
