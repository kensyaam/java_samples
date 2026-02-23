
<!-- @import "[TOC]" {cmd="toc" depthFrom=1 depthTo=2 orderedList=false} -->

<!-- code_chunk_output -->

- [Model Parser CLI](#model-parser-cli)
  - [特徴](#特徴)
  - [必要条件](#必要条件)
  - [ビルド方法](#ビルド方法)
  - [使用方法](#使用方法)
  - [使用例](#使用例)
  - [出力フォーマット](#出力フォーマット)
  - [型変換ルール](#型変換ルール)
  - [必須判定](#必須判定)
  - [詳細展開（-v, --verbose）](#詳細展開-v--verbose)
- [Response Analyzer CLI](#response-analyzer-cli)
  - [特徴](#特徴-1)
  - [ビルド方法](#ビルド方法-1)
  - [使用方法](#使用方法-1)
  - [使用例](#使用例-1)
  - [レスポンス解析（ResponseAnalysisシート）](#レスポンス解析responseanalysisシート)
  - [リクエスト解析（RequestAnalysisシート）](#リクエスト解析requestanalysisシート)
  - [内部処理概要](#内部処理概要)

<!-- /code_chunk_output -->

###### 参考
- https://docs.spring.io/spring-framework/docs/3.2.x/spring-framework-reference/html/mvc.html?utm_source=chatgpt.com#mvc-servlet

----
# Model Parser CLI

JavaモデルクラスをパースしてAPI仕様書の元ネタとなるExcelファイルを生成するCLIツールです。

Spring MVCなどのレガシーシステムのモデルクラス（DTOなど）を解析し、フィールド情報をExcelファイルに出力します。

## 特徴

- **Spoonによるソース解析**: 依存ライブラリがなくても解析可能（NoClasspathモード）
- **Javadoc対応**: フィールドのJavadocから論理名と説明を自動抽出
- **型変換**: Java型をJSON型に自動マッピング
- **バリデーションアノテーション認識**: `@NotNull`等のアノテーションから必須項目を判定
- **親クラスフィールド継承**: 親クラスを再帰的に辿ってフィールドを収集（定義元クラス名で区別可能）
- **同一クラス名対応**: 異なるパッケージに同一の単純クラス名が存在してもエラーなく処理

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

ビルド成果物はプロジェクトルート直下に `model-parser.jar` が生成されます。

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
| `-v, --verbose` | 内部モデルの構造をドット記法で展開して出力する | - | - |
| `-rj, --real-json` | 実際のクラスをロードしてJSONを生成する | - | - |
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

Excelファイルに以下のシートが作成されます。

### 1. Index シート

解析対象クラスの一覧と概要を表示します。クラス名をクリックすることで「モデルデータ定義」シートの該当箇所へジャンプできます。

| 列 | ヘッダー名 | 説明 |
| :--- | :--- | :--- |
| A | パッケージ | クラスが属するパッケージ名 |
| B | クラス名 | クラスの単純名（モデルデータ定義へのリンク） |
| C | 概要 | Javadocのサマリー |

### 2. モデルデータ定義 シート

各クラスのフィールド詳細情報を出力します。

| 列 | ヘッダー名 | 説明 |
| :--- | :--- | :--- |
| A-E | 論理名 | Javadocのタイトル（ネストレベルに応じて字下げ） |
| F | JSONキー | フィールド名（JSONのキーとして使用、末端キー名のみ） |
| G | JSONキー(パス表記) | フィールド名（完全なドット区切りパス） |
| H | JSON型 | string, number, boolean, array, object |
| I | 内部要素/参照 | 配列やオブジェクトの内部型（クリックで該当クラスへジャンプ） |
| J | 必須 | true または false 等 |
| K | サンプル値 | 型に応じた自動生成値 |
| L | クラス名 | 所属クラスの単純名 (Javaヘッダー色)  |
| M | 完全修飾クラス名 | パッケージ名を含む完全修飾クラス名 (Javaヘッダー色) |
| N | Javaフィールド名 | フィールド変数名 (Javaヘッダー色) |
| O | Java型 | Javaの型名 (Javaヘッダー色) |
| P | 定義元クラス | 親クラスから継承したフィールドの場合、親クラス名を表示 (Javaヘッダー色) |

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

## 詳細展開（-v, --verbose）

`-v` オプションを指定すると、内部参照されている非標準クラスのフィールドを再帰的に展開して出力します。

- **ドット記法**: ネストされたフィールドは `user.address.street` のように出力されます。
- **配列記法**: 配列やコレクション内のフィールドは `items[].id` のように出力されます。
- **循環参照防止**: 同一の型が再帰パスに既に存在する場合は展開を停止します。


---

# Response Analyzer CLI

Spring MVC ControllerとJSPを解析して、レスポンス（画面表示）で**実際に使用されているフィールド**を特定するCLIツールです。

## 特徴

- **Controller解析**: `addAttribute`, `put`, `addObject` のメソッド呼び出しと `@ModelAttribute` 引数を検出
- **View名の定数解決**: `return VIEW_USER_DETAIL;` のような定数参照を自動解決
- **複数return対応**: `if/else`等で複数のreturn文がある場合、それぞれのViewを解析
- **変数追跡**: `return viewName;` のような変数returnの場合、宣言時初期化と全代入文の値を候補として解析
- **JSP解析**: EL式 `${xxx.yyy}` を抽出し、インクルードファイルも再帰的に解析
- **使用状況判定**: 各フィールドがJSPで参照されているか（USED/UNUSED）を判定
- **継承対応**: Controllerの親クラス・インターフェースも再帰的に走査

## ビルド方法

```bash
# Response Analyzer用のJARを生成
./gradlew responseAnalyzerJar
```

ビルド成果物はプロジェクトルート直下に `response-analyzer.jar` として生成されます。

## 使用方法

```bash
java -jar response-analyzer.jar [options]
```

### オプション

| オプション | 説明 | 必須 | デフォルト |
| :--- | :--- | :--- | :--- |
| `-s <dirs>` | ソースディレクトリ（カンマ区切りで複数指定可） | ○ | - |
| `-j <dir>` | JSPルートディレクトリ | ○ | - |
| `-c <file>` | 対象Controllerリストファイル | ○ | - |
| `-cp <paths>` | クラスパスエントリ（カンマ区切りで複数指定可） | - | - |
| `-cl <level>` | Javaコンプライアンスレベル | - | 21 |
| `-e <encoding>` | ソースコードのエンコーディング | - | UTF-8 |
| `-o <file>` | 出力Excelファイル名 | - | response-analysis.xlsx |
| `-h, --help` | ヘルプメッセージを表示 | - | - |

### 対象Controllerリストファイル（-c）

解析対象のControllerを1行1クラスで記載したテキストファイルを指定します。

```text
# コメント行
com.example.controller.UserController
com.example.controller.OrderController
```

## 使用例

```bash
java -jar response-analyzer.jar \
  -s src/main/java \
  -j src/main/webapp/WEB-INF/jsp \
  -c controllers.txt \
  -o response-analysis.xlsx
```

## レスポンス解析（ResponseAnalysisシート）

### 出力フォーマット

Excelファイルに「ResponseAnalysis」シートが作成され、以下のカラムが出力されます。

| 列 | ヘッダー名 | 説明 |
| :--- | :--- | :--- |
| A | Controller | コントローラ名 |
| B | Method | メソッド名 |
| C | View Path | View名（定数解決後） |
| D | Attribute Name | `addAttribute` のキーまたは引数名 |
| E | JSP Reference | 発見されたEL式 |
| F | Java Class | Javaクラス名 |
| G | Java Field | フィールド名 |
| H | 使用状況 | **USED** または **UNUSED** |
| I | 属性の由来 | addAttribute / put / Argument 等 |
| J | 警告/備考 | インクルード情報、スクリプトレット警告等 |

### 検出パターン

#### Model属性の追加

以下のパターンが検出されます。

```java
// パターン1: Model.addAttribute
model.addAttribute("userDto", userDto);

// パターン2: ModelMap.put
modelMap.put("orderDto", orderDto);

// パターン3: ModelAndView.addObject
mav.addObject("itemDto", itemDto);

// パターン4: メソッド引数（@ModelAttribute）
public String edit(@ModelAttribute("userForm") UserDto userDto) { ... }
```

#### フレームワーク型の除外

メソッド引数から暗黙的にModel属性を抽出する際、以下の型は除外されます。

- `Model`, `ModelMap`, `Map`, `ModelAndView`
- `HttpServletRequest`, `HttpServletResponse`, `HttpSession`
- `BindingResult`, `Errors`, `RedirectAttributes`
- `Principal`, `Authentication`, `Locale`
- プリミティブ型（`String`, `int`, `Integer` 等）

#### View名の定数解決

```java
private static final String VIEW_USER_DETAIL = "user/detail";

@GetMapping("/detail")
public String showDetail(Model model) {
    return VIEW_USER_DETAIL;  // → "user/detail" に解決される
}
```

#### View名の複数return対応

```java
@GetMapping("/conditional")
public String showConditional(Model model, boolean isVip) {
    model.addAttribute("userDto", userDto);
    if (isVip) {
        return "user/vip";    // → 解析対象
    } else {
        return "user/normal"; // → 解析対象
    }
}
```

#### View名の変数追跡

```java
@GetMapping("/variable-return")
public String showVariableReturn(Model model, int userType) {
    model.addAttribute("userDto", userDto);
    String viewName = "user/default";  // → 候補1
    if (userType == 1) {
        viewName = "user/special";     // → 候補2
    }
    return viewName;  // 全候補が解析対象
}
```

#### スコープ参照の検出

JSP内でのスコープ参照を検出し、「属性の由来」列に情報を付加します。

##### 検出対象パターン

**EL式でのスコープ参照**:

```jsp
${requestScope.errorMessage}
${sessionScope.loginUser.name}
${applicationScope.config.setting}
${pageScope.tempData}
```

**スクリプトレット内での参照**:

```jsp
<% request.getAttribute("errorMessage") %>
<% session.getAttribute("loginUser") %>
<% application.getAttribute("config") %>
<% pageContext.getAttribute("tempData") %>
```

**チェーン呼び出し**:

```jsp
<% request.getSession().getAttribute("user") %>
```

##### 出力例

スコープ参照が検出されると、「属性の由来」列にスコープ情報が追記されます。


## リクエスト解析（RequestAnalysisシート）

Response Analyzerは、JSP内のフォーム定義と入力要素も解析し、REST APIリクエストボディの設計に必要な情報を抽出します。

### 対象タグ

以下のフォーム・入力要素タグを検出します。

**HTML標準**:
- `<form>`, `<input>`, `<select>`, `<textarea>`, `<button>`

**Spring Form Tag**:
- `<form:form>`, `<form:input>`, `<form:password>`, `<form:hidden>`, `<form:checkbox>`, `<form:radiobutton>`, `<form:select>`, `<form:textarea>` 等

**Struts HTML Tag**:
- `<html:form>`, `<html:text>`, `<html:password>`, `<html:hidden>`, `<html:checkbox>`, `<html:radio>`, `<html:select>`, `<html:textarea>` 等

### 出力フォーマット

Excelファイルに「RequestAnalysis」シートが追加され、以下のカラムが出力されます。

| 列 | ヘッダー名 | 説明 |
| :--- | :--- | :--- |
| A | JSPファイルパス | JSPファイルの相対パス |
| B | Form Action | フォームの送信先URL |
| C | Form Method | HTTPメソッド（GET/POST） |
| D | Root Model | `modelAttribute`または`commandName`の値 |
| E | Input Tag | 使用されているタグ名 |
| F | Input Type | `type`属性の値または推定タイプ |
| G | Parameter Name | `name`/`path`/`property`属性の値 |
| H | Max Length | `maxlength`属性の値 |
| I | Required | 必須属性の有無（true/空白） |
| J | イベント | イベントハンドラ（`on*`属性） |
| K | 備考 | ボタン/リンクの内部テキスト、ボタンのvalue属性値、リンクのhref属性値 |
| L | JSONキー | パラメータ名の末尾（ネスト時の参考値） |
| M | JSONキー(ネスト) | パラメータ名（ネスト構造の参考値） |

### ドット記法によるネスト構造

パラメータ名がドット区切り（例: `user.address.zipCode`）の場合、JSONのネスト構造として識別されます。

```jsp
<form:input path="user.address.zipCode" />
```

上記の場合、出力は以下のようになります:
- **Parameter Name**: `user.address.zipCode`
- **JSON Key Estimate**: `zipCode`
- **Nest Path**: `user.address`

これにより、以下のようなJSON構造を設計する際の参考になります:

```json
{
  "user": {
    "address": {
      "zipCode": "1234567"
    }
  }
}
```

## 内部処理概要

### analyzeJsp() メソッドの処理内容
JSPファイルを解析し、EL式、スコープ参照、JSTL変数定義、フォーム使用状況などを抽出してJspAnalysisResultにまとめます。

#### 抽出処理

| 処理 | 使用パターン | 抽出先 (JspAnalysisResult) |
| :--- | :--- | :--- |
| EL式抽出 | EL_PATTERN | elExpressions |
| スクリプトレット検出 | SCRIPTLET_PATTERN | hasScriptlets |
| インクルード処理 | JSP_INCLUDE_PATTERN | includedJsps + 再帰解析 |
| c:forEachマッピング | C_FOREACH_PATTERN | forEachVarToItems |
| c:setマッピング | C_SET_PATTERN | cSetVarToValue |
| ELスコープ参照 | EL_SCOPE_PATTERN | scopeReferenceDetails |
| スクリプトレットスコープ参照 | SCRIPTLET_SCOPE_PATTERN | scopeReferenceDetails |
| チェーン呼び出し参照 | CHAIN_SCOPE_PATTERN | scopeReferenceDetails |
| フォーム解析 | analyzeJspForms() | formUsages |

### matchAttributeToJsp() メソッドの処理内容
ControllerのModel属性とJSP内のEL式・フォームパスを突き合わせ、各フィールドの使用状況（USED/UNUSED）を判定します。
