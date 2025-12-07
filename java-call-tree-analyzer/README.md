# call-tree-analyzer

<!-- @import "[TOC]" {cmd="toc" depthFrom=2 depthTo=5 orderedList=false} -->

<!-- code_chunk_output -->

- [静的解析ツール](#静的解析ツール)
  - [ビルド](#ビルド)
  - [解析実行](#解析実行)
  - [機能](#機能)
  - [参考： 動作検証に使えそうなプロジェクト](#参考-動作検証に使えそうなプロジェクト)
- [可視化ツール](#可視化ツール)
  - [事前準備](#事前準備)
  - [使い方](#使い方)
  - [基本的な使い方](#基本的な使い方)
    - [Tips](#tips)
  - [機能説明](#機能説明)
    - [除外機能](#除外機能)
      - [除外ルールファイルのフォーマット](#除外ルールファイルのフォーマット)
      - [除外ルールの例](#除外ルールの例)
      - [除外機能の使用例](#除外機能の使用例)
    - [SQL抽出・テーブル分析機能](#sql抽出テーブル分析機能)
      - [SQL文の抽出](#sql文の抽出)
      - [テーブル使用状況の分析](#テーブル使用状況の分析)
      - [テーブルリストファイル (`table_list.tsv`) のフォーマット](#テーブルリストファイル-table_listtsv-のフォーマット)
    - [Excel出力機能](#excel出力機能)
      - [使用方法](#使用方法)
      - [エントリーポイントファイルの形式](#エントリーポイントファイルの形式)
      - [Excel出力フォーマット](#excel出力フォーマット)

<!-- /code_chunk_output -->


----

## 静的解析ツール

### ビルド

```bash
# ビルド & 実行可能jar作成
./gradlew build shadowJar
```

### 解析実行

```bash
$ java -jar call-tree-analyzer-1.0.0.jar -h                                                     
usage: CallTreeAnalyzer
 -s,--source <arg>             解析対象のソースディレクトリ（複数指定可、カンマ区切り）
 -cp,--classpath <arg>         依存ライブラリのJARファイルまたはディレクトリ（複数指定可、カンマ区切り）
 -xml,--xml-config <arg>       Spring設定XMLファイルのディレクトリ（複数指定可、カンマ区切り）
 -o,--output <arg>             出力ファイルパス（デフォルト: call-tree.tsv）
 -f,--format <arg>             出力フォーマット（tsv/json/graphml、デフォルト: tsv）
 -d,--debug                    デバッグモードを有効化
 -cl,--complianceLevel <arg>   Javaのコンプライアンスレベル（デフォルト: 21）
 -e,--encoding <arg>           ソースコードの文字エンコーディング（デフォルト: UTF-8）
 -h,--help                     ヘルプを表示
```

```bash
### 基本的な使い方

## 解析対象のパス設定
# ソース
SRC_DIR="/path/to/src"
# 依存ライブラリのクラスパス
LIB_DIR="/path/to/libdir1,/path/to/libdir2"
# Spring設定XMLファイルのディレクトリ
XML_DIR="/path/to/xmldir"

# クラスパスが複数あり、かつ、Git Bashから実行する場合を考慮
LIB_DIR=$([[ -n "$MSYSTEM" ]] && printf '%s' "$LIB_DIR" | tr ',' '\n' | cygpath -w -f - | paste -sd',' - || printf '%s' "$LIB_DIR")

# 解析実行
java -jar call-tree-analyzer-1.0.0.jar -s "$SRC_DIR" -cp "$LIB_DIR" -xml "$XML_DIR" -cl 21 -e UTF-8 -o call-tree.tsv -d

# JSON形式で出力
java -jar call-tree-analyzer-1.0.0.jar -s "$SRC_DIR" -cp "$LIB_DIR" -xml "$XML_DIR" -cl 21 -e UTF-8 -o output.json -f json


# gradlewで実行する例
./gradlew run --args="-s /path/to/your/source -o call-tree.tsv"
```

### 機能

spoonを使って静的解析を行い、メソッドの呼び出し関係やjavadoc、アノテーションなどの情報を抽出する。
呼び出しツリーなどの出力はPythonの可視化ツールで行う。

### 参考： 動作検証に使えそうなプロジェクト

```bash
git clone https://github.com/spring-petclinic/spring-framework-petclinic
cd spring-framework-petclinic

# 依存ライブラリをプロジェクト内に取得
mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
```

```bash
java -jar call-tree-analyzer-1.0.0.jar 
  -s ../../ext/spring-framework-petclinic/src/main 
  -cp ../../ext/spring-framework-petclinic/target/dependency 
  -xml ../../ext/spring-framework-petclinic/src/main/resources/spring 
  -o dist/call-tree.tsv -d
```

## 可視化ツール

### 事前準備

```bash
# ツリーをExcelにエクスポートする場合のみ
python -m venv .venv
source .venv/Scripts/activate
pip install openpyxl
pip install types-openpyxl

# SQL抽出機能を使う場合
pip install sqlparse

# .pyをexeに変換する場合
pip install pyinstaller pillow
#   app.pngをapp.icoに変換
python -c "from PIL import Image; Image.open('app.png').resize((256,256), Image.LANCZOS).save('app.ico')"
pyinstaller --onefile --noconsole --icon=app.ico -n CallTreeVisualizer call_tree_visualizer.py
```

### 使い方

```bash
$ python call_tree_visualizer.py --help
usage: call_tree_visualizer.py [-h] [--exclusion-file EXCLUSION_FILE] [--output-tsv-encoding OUTPUT_TSV_ENCODING]
                               tsv_file
                               {list,search,forward,reverse,export,export-excel,extract-sql,analyze-tables} ...

呼び出しツリー可視化スクリプト - TSVファイルから呼び出しツリーを生成します

positional arguments:
  tsv_file              TSVファイルのパス
  {list,search,forward,reverse,export,export-excel,extract-sql,analyze-tables}
                        サブコマンド
    list                エントリーポイント候補を表示
    search              キーワードでメソッドを検索
    forward             指定メソッドからの呼び出しツリーを表示
    reverse             指定メソッドへの呼び出し元ツリーを表示
    export              ツリーをファイルにエクスポート
    export-excel        ツリーをExcelにエクスポート
    extract-sql         SQL文を抽出してファイル出力
    analyze-tables      SQLファイルから使用テーブルを検出

options:
  -h, --help            show this help message and exit
  --exclusion-file EXCLUSION_FILE
                        除外ルールファイルのパス (デフォルト: exclusion_rules.txt)
  --output-tsv-encoding OUTPUT_TSV_ENCODING
                        出力するTSVのエンコーディング (デフォルト: Shift_JIS (Excelへの貼付けを考慮))

除外ルールファイルのフォーマット:
  <クラス名 or メソッド名><TAB><I|E>
  I: 対象自体を除外
  E: 対象は表示するが、配下の呼び出しを除外

テーブルリストファイル (table_list.tsv) のフォーマット:
  <物理テーブル名><TAB><論理テーブル名><TAB><補足情報>

使用例:
  python call_tree_visualizer.py call-tree.tsv list
  python call_tree_visualizer.py call-tree.tsv list --no-strict --min-calls 5
  python call_tree_visualizer.py call-tree.tsv forward 'com.example.Main#main(String[])'
  python call_tree_visualizer.py call-tree.tsv reverse 'com.example.Service#process()'
  python call_tree_visualizer.py call-tree.tsv export 'com.example.Main#main(String[])' tree.html --format html
  python call_tree_visualizer.py call-tree.tsv export-excel call_trees.xlsx --entry-points entry_points.txt
  python call_tree_visualizer.py call-tree.tsv extract-sql --output-dir ./output/sqls
  python call_tree_visualizer.py call-tree.tsv analyze-tables --sql-dir ./output/sqls
```

### 基本的な使い方

```bash
# エントリーポイントを見つける
#   厳密モード（デフォルト）
python call_tree_visualizer.py call-tree.tsv list
#   緩和モード（呼び出し数で絞り込み）
python call_tree_visualizer.py call-tree.tsv list --no-strict --min-calls 5

# キーワードでメソッドを検索
KEYWORD="main"
python call_tree_visualizer.py call-tree.tsv search "$KEYWORD"

# 呼び出しツリーを表示
METHOD="com.example.Main#main(String[])"
#   実装クラス候補も追跡（デフォルト）
python call_tree_visualizer.py call-tree.tsv forward "$METHOD"
#   実装クラス候補を追跡しない
python call_tree_visualizer.py call-tree.tsv forward "$METHOD" --no-follow-impl

# 逆引きツリー（誰がこのメソッドを呼んでいるか）
METHOD="com.example.UserDaoImpl#save(User)"
#   オーバーライド元/インターフェースメソッドも追跡（デフォルト）
python call_tree_visualizer.py call-tree.tsv reverse "$METHOD"
#   オーバーライド元/インターフェースメソッドを追跡しない
python call_tree_visualizer.py call-tree.tsv reverse "$METHOD" --no-follow-override

# ファイルにエクスポート
#   テキスト形式
python call_tree_visualizer.py call-tree.tsv export "$METHOD" tree.txt --format text
#   Markdown形式
python call_tree_visualizer.py call-tree.tsv export "$METHOD" tree.md --format markdown
#   HTML形式（ブラウザで開ける）
python call_tree_visualizer.py call-tree.tsv export "$METHOD" tree.html --format html

# Excel形式で呼び出しツリーを一括出力
#   エントリーポイントファイルを指定する場合
python call_tree_visualizer.py call-tree.tsv export-excel call_trees.xlsx --entry-points entry_points.txt
#   エントリーポイントファイルを指定しない場合（厳密モードで検出されるすべてのエントリーポイントが対象）
python call_tree_visualizer.py call-tree.tsv export-excel call_trees.xlsx

```

#### Tips

```bash
# エントリーポイントの一覧から呼び出しツリーを出力するメソッドを選択
# (fzfのインストールが必要)
METHOD=$(LIST=$(python call_tree_visualizer.py call-tree.tsv list | grep -E "^[0-9]" | sed -E 's/^[0-9]+\. //g'); echo "$LIST" | fzf)
python call_tree_visualizer.py call-tree.tsv forward "$METHOD"

# エントリーポイントのテキスト形式の呼び出しツリーを一括出力
python call_tree_visualizer.py call-tree.tsv list | grep -E "^[0-9]+\." | sed -E "s|^[0-9]+\. ||g" | while read -r line; do
  python call_tree_visualizer.py call-tree.tsv forward "$line";
done

```

### 機能説明

#### 除外機能

特定のクラスやメソッドをツリーから除外できます。

##### 除外ルールファイルのフォーマット

`exclusion_rules.txt`というファイル（デフォルト）を作成し、各行に以下の形式で記述します：

```txt
<クラス名 or メソッド名><TAB><I|E>
```

- **Iモード**: 対象自体を除外（そのメソッド/クラスおよび配下のツリー全体を除外）
- **Eモード**: 対象は表示するが、配下の呼び出しを除外（そのメソッド/クラスまでは表示するが、それ以降の展開をスキップ）

##### 除外ルールの例

```txt
# Iモード: INFOログ出力を除外
org.slf4j.Logger#info(String)<TAB>I

# Iモード: ログ出力関連を完全に除外
org.slf4j.Logger<TAB>I

# Iモード: クラスを問わず、debug(String)を除外
debug(String)<TAB>I

# Eモード: java.util.List は表示するが、その配下は展開しない
java.util.List<TAB>E
java.util.ArrayList#add(Object)<TAB>E
```

`<TAB>` : ハードタブ

##### 除外機能の使用例

```bash
# デフォルトの除外ファイル(exclusion_rules.txt)を使用
python call_tree_visualizer.py call-tree.tsv forward "$METHOD"

# カスタム除外ファイルを指定
python call_tree_visualizer.py call-tree.tsv --exclusion-file my_exclusions.txt forward "$METHOD"
```

#### SQL抽出・テーブル分析機能

##### SQL文の抽出

TSVファイルから検出されたSQL文を個別のSQLファイルとして出力します。

```bash
# 基本的な使い方（デフォルトでは ./found_sql に出力）
python call_tree_visualizer.py call-tree.tsv extract-sql

# 出力先ディレクトリを指定
python call_tree_visualizer.py call-tree.tsv extract-sql --output-dir ./output/sqls
```

###### 出力ファイル名の規則

- 同一メソッド内で検出されたSQL文が1つ: `<メソッド名>.sql`
- 同一メソッド内で検出されたSQL文が複数: `<メソッド名>_<通番>.sql`

###### SQL整形

- `sqlparse` ライブラリなどを使用してSQL文をゆるく自動整形
- キーワードの大文字化、適切なインデントと改行を適用
- 構文エラーがあっても処理を継続（エラーで落ちない設計）

##### テーブル使用状況の分析

出力されたSQLファイルから使用テーブルを検出し、標準出力に表示します。

```bash
# 基本的な使い方
python call_tree_visualizer.py call-tree.tsv analyze-tables

# SQLディレクトリとテーブルリストファイルを指定
python call_tree_visualizer.py call-tree.tsv analyze-tables --sql-dir ./output/sqls --table-list ./my_tables.tsv
```

##### テーブルリストファイル (`table_list.tsv`) のフォーマット

```txt
<物理テーブル名><TAB><論理テーブル名><TAB><補足情報>
```

例:

```txt
users<TAB>ユーザーマスタ<TAB>基本情報を管理
orders<TAB>注文テーブル<TAB>注文情報
order_details<TAB>注文明細<TAB>注文の詳細情報
```

`<TAB>`: ハードタブ

**出力フォーマット:**

```txt
<SQLファイル名><TAB><物理テーブル名><TAB><論理テーブル名><TAB><補足情報>
```

**使用例:**

```bash
# SQL抽出 → テーブル分析の一連の流れ
python call_tree_visualizer.py call-tree.tsv extract-sql
python call_tree_visualizer.py call-tree.tsv analyze-tables > table_usage.tsv
```

#### Excel出力機能

呼び出しツリーを構造化されたExcel形式で出力します。複数のエントリーポイントからの呼び出しツリーを1つのExcelファイルにまとめて出力できます。

##### 使用方法

```bash
# エントリーポイントをファイルで指定
python call_tree_visualizer.py call-tree.tsv export-excel output.xlsx --entry-points entry_points.txt

# 厳密モードのすべてのエントリーポイントを出力（ファイル指定なし）
python call_tree_visualizer.py call-tree.tsv export-excel output.xlsx

# 深度を指定
python call_tree_visualizer.py call-tree.tsv export-excel output.xlsx --entry-points entry_points.txt --depth 15
```

##### エントリーポイントファイルの形式

1行に1つのメソッドシグネチャを記載します。空行と`#`で始まるコメント行はスキップされます。

```txt
# エントリーポイント例
com.example.Main#main(String[])
com.example.api.UserController#getUser(Long)
com.example.service.OrderService#processOrder(Order)
```

##### Excel出力フォーマット

- **A列**: 呼び出しツリーの先頭メソッド（fully qualified name）
- **B列**: 呼び出しメソッド（fully qualified name）
- **C列**: 呼び出しメソッドのパッケージ名
- **D列**: 呼び出しメソッドのクラス名
- **E列**: 呼び出しメソッドのメソッド名（simple name）
- **F列**: 呼び出しメソッドのJavadoc
- **G列**: 呼び出しメソッドが呼び元の親クラスのメソッドなら"親クラス"、実装クラスへ展開したものなら"実装クラスへの展開"
- **H列**: SQL文がある場合: ●
- **L列以降**: 呼び出しツリー（メソッドからパッケージ名は除外、列のインデントで階層表現）
- **AZ列**: SQL文

その他:

- フォント: Meiryo UI
- L列以降の列幅: 5
- 実装クラス候補がある場合は自動的に追跡・展開
- 循環参照は `[循環参照]` として表示され、それ以上展開されない
- 除外ルールファイルにも対応
