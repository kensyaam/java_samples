# call-tree-analyzer

<!-- @import "[TOC]" {cmd="toc" depthFrom=2 depthTo=5 orderedList=false} -->

<!-- code_chunk_output -->

- [静的解析ツール](#静的解析ツール)
  - [ビルド](#ビルド)
  - [解析実行](#解析実行)
  - [参考： 動作検証に使えそうなプロジェクト](#参考-動作検証に使えそうなプロジェクト)
- [可視化ツール](#可視化ツール)
  - [事前準備](#事前準備)
  - [使い方](#使い方)
  - [サブコマンド](#サブコマンド)
    - [list : エントリーポイントの一覧出力](#list--エントリーポイントの一覧出力)
    - [search : キーワードでメソッドを検索](#search--キーワードでメソッドを検索)
    - [forward : 呼び出しツリー出力](#forward--呼び出しツリー出力)
    - [reverse : 逆引きツリー出力（誰がこのメソッドを呼んでいるか）](#reverse--逆引きツリー出力誰がこのメソッドを呼んでいるか)
    - [export : 呼び出しツリーを指定形式のファイルにエクスポート](#export--呼び出しツリーを指定形式のファイルにエクスポート)
    - [export-excel : 一括で呼び出しツリーをExcelファイルに出力](#export-excel--一括で呼び出しツリーをexcelファイルに出力)
    - [extract-sql : SQL文を抽出してファイル出力](#extract-sql--sql文を抽出してファイル出力)
    - [analyze-tables : SQLファイルから使用テーブルを検出](#analyze-tables--sqlファイルから使用テーブルを検出)
    - [Tips](#tips)
  - [除外ルールファイルについて](#除外ルールファイルについて)

<!-- /code_chunk_output -->


----

## 静的解析ツール

`spoon`ライブラリを使って静的解析を行い、メソッドの呼び出し関係やjavadoc、アノテーションなどの情報を抽出する。
呼び出しツリーなどの出力はPythonの可視化ツールで行う。

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
 -w,--words <arg>              リテラル文字列の検索ワードファイルのパス（デフォルト: search_words.txt）
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

静的解析ツールの出力結果を可視化するツール。

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
pyinstaller --onefile --icon=app.ico -n CallTreeVisualizer call_tree_visualizer.py
```

### 使い方

```bash
$ python call_tree_visualizer.py --help
usage: call_tree_visualizer.py [-h] [--exclusion-file EXCLUSION_FILE] [--output-tsv-encoding OUTPUT_TSV_ENCODING]
                               tsv_file
                               {list,search,forward,reverse,export,export-excel,extract-sql,analyze-tables} ...

呼び出しツリー可視化スクリプト - TSVファイルから呼び出しツリーなどを生成する

positional arguments:
  tsv_file              TSVファイル（静的解析ツールの出力）のパス
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

サブコマンドのヘルプを表示する例:
  python call_tree_visualizer.py list --help
  python call_tree_visualizer.py forward --help
  python call_tree_visualizer.py reverse --help
```

### サブコマンド

#### list : エントリーポイントの一覧出力

```bash
$ python call_tree_visualizer.py call-tree.tsv list --help
usage: call_tree_visualizer.py tsv_file list [-h] [--no-strict] [--min-calls MIN_CALLS] [--tsv]

options:
  -h, --help            show this help message and exit
  --no-strict           緩和モード（デフォルトは厳密モード）
  --min-calls MIN_CALLS
                        エントリーポイントの最小呼び出し数 (デフォルト: 1)
  --tsv                 TSV形式で出力
```

```bash
# 厳密モード（デフォルト）
python call_tree_visualizer.py call-tree.tsv list
# 緩和モード（呼び出し数で絞り込み）
python call_tree_visualizer.py call-tree.tsv list --no-strict --min-calls 5
```

#### search : キーワードでメソッドを検索

```bash
$ python call_tree_visualizer.py call-tree.tsv search --help
usage: call_tree_visualizer.py tsv_file search [-h] keyword

positional arguments:
  keyword               検索キーワード

options:
  -h, --help            show this help message and exit
```

```bash
KEYWORD="main"
python call_tree_visualizer.py call-tree.tsv search "$KEYWORD"
```

#### forward : 呼び出しツリー出力

```bash
$ python call_tree_visualizer.py call-tree.tsv forward --help
usage: call_tree_visualizer.py tsv_file forward [-h] [--depth DEPTH] [--no-class] [--no-sql] [--no-follow-impl] method

positional arguments:
  method                起点メソッド

options:
  -h, --help            show this help message and exit
  --depth DEPTH         ツリーの最大深度 (デフォルト: 50)
  --no-class            クラス情報を非表示
  --no-sql              SQL情報を非表示
  --no-follow-impl      実装クラス候補を追跡しない
```

```bash
METHOD="com.example.Main#main(String[])"
# 実装クラス候補も追跡（デフォルト）
python call_tree_visualizer.py call-tree.tsv forward "$METHOD"
# 実装クラス候補を追跡しない
python call_tree_visualizer.py call-tree.tsv forward "$METHOD" --no-follow-impl
```

#### reverse : 逆引きツリー出力（誰がこのメソッドを呼んでいるか）

- 逆引きツリー（誰がこのメソッドを呼んでいるか）を表示
- ツリー出力後、最終到達点のメソッド一覧（最上位の呼び元メソッド）を表示

```bash
$ python call_tree_visualizer.py call-tree.tsv reverse --help
usage: call_tree_visualizer.py tsv_file reverse [-h] [--depth DEPTH] [--no-class] [--no-follow-override] method

positional arguments:
  method                対象メソッド

options:
  -h, --help            show this help message and exit
  --depth DEPTH         ツリーの最大深度 (デフォルト: 50)
  --no-class            クラス情報を非表示
  --no-follow-override  オーバーライド元を追跡しない
```

```bash
METHOD="com.example.UserDaoImpl#save(User)"
# オーバーライド元/インターフェースメソッドも追跡（デフォルト）
python call_tree_visualizer.py call-tree.tsv reverse "$METHOD"
# オーバーライド元/インターフェースメソッドを追跡しない
python call_tree_visualizer.py call-tree.tsv reverse "$METHOD" --no-follow-override
```

#### export : 呼び出しツリーを指定形式のファイルにエクスポート

```bash
$ python call_tree_visualizer.py call-tree.tsv export --help
usage: call_tree_visualizer.py tsv_file export [-h] [--format {text,markdown,html}] [--depth DEPTH] [--no-follow-impl] method output_file

positional arguments:
  method                起点メソッド
  output_file           出力ファイル名

options:
  -h, --help            show this help message and exit
  --format {text,markdown,html}
                        出力形式 (デフォルト: text)
  --depth DEPTH         ツリーの最大深度 (デフォルト: 50)
  --no-follow-impl      実装クラス候補を追跡しない
```

```bash
# テキスト形式
python call_tree_visualizer.py call-tree.tsv export "$METHOD" tree.txt --format text
# Markdown形式
python call_tree_visualizer.py call-tree.tsv export "$METHOD" tree.md --format markdown
# HTML形式
python call_tree_visualizer.py call-tree.tsv export "$METHOD" tree.html --format html
```

#### export-excel : 一括で呼び出しツリーをExcelファイルに出力

呼び出しツリーを構造化されたExcel形式で出力する。
複数のエントリーポイントからの呼び出しツリーを1つのExcelファイルにまとめて出力できる。

```bash
$ python call_tree_visualizer.py call-tree.tsv export-excel --help
usage: call_tree_visualizer.py tsv_file export-excel [-h] [--entry-points ENTRY_POINTS] [--depth DEPTH] [--no-follow-impl] [--no-tree] [--no-sql]
                                                     output_file

positional arguments:
  output_file           出力Excelファイル名

options:
  -h, --help            show this help message and exit
  --entry-points ENTRY_POINTS
                        エントリーポイントファイル（指定しない場合は厳密モードのエントリーポイントを使用）
  --depth DEPTH         ツリーの最大深度 (デフォルト: 20)
  --no-follow-impl      実装クラス候補を追跡しない
  --no-tree             L列以降の呼び出しツリーを出力しない
  --no-sql              AZ列のSQL文を出力しない
```

```bash
# エントリーポイントファイルを指定する場合
python call_tree_visualizer.py call-tree.tsv export-excel call_trees.xlsx --entry-points entry_points.txt

# エントリーポイントファイルを指定しない場合（厳密モードで検出されるすべてのエントリーポイントが対象）
python call_tree_visualizer.py call-tree.tsv export-excel call_trees.xlsx
```

###### エントリーポイントファイルの形式

1行に1つのメソッドシグネチャを記載する。
空行と`#`で始まるコメント行はスキップする。

```txt
# エントリーポイント例
com.example.Main#main(String[])
com.example.api.UserController#getUser(Long)
com.example.service.OrderService#processOrder(Order)
```

###### Excel出力フォーマット

- **A列**: 呼び出しツリーの先頭メソッド（fully qualified name）
- **B列**: 呼び出しメソッド（fully qualified name）
- **C列**: 呼び出しメソッドのパッケージ名
- **D列**: 呼び出しメソッドのクラス名
- **E列**: 呼び出しメソッドのメソッド名（simple name）
- **F列**: 呼び出しメソッドのJavadoc
- **G列**: 呼び出しメソッドが呼び元の親クラスのメソッドなら`親クラス`、実装クラスへ展開したものなら`実装クラスへの展開`
- **H列**: 呼び出しメソッド内にSQL文がある場合: `●`
- **L列以降**: 呼び出しツリー（メソッドからパッケージ名は除外、列のインデントで階層表現）
- **AZ列**: 呼び出しメソッド内のSQL文

その他:

- フォント: Meiryo UI
- L列以降の列幅: 5
- 循環参照は `[循環参照]` として表示され、それ以上展開されない
- 除外ルールファイルにも対応

#### extract-sql : SQL文を抽出してファイル出力

静的解析ツールで検出されたSQL文をSQLファイルとして出力する。

```bash
$ python call_tree_visualizer.py call-tree.tsv extract-sql --help
usage: call_tree_visualizer.py tsv_file extract-sql [-h] [--output-dir OUTPUT_DIR]

options:
  -h, --help            show this help message and exit
  --output-dir OUTPUT_DIR
                        SQL出力先ディレクトリ (デフォルト: ./found_sql)
```

```bash
# 基本的な使い方（デフォルトでは ./found_sql に出力）
python call_tree_visualizer.py call-tree.tsv extract-sql

# 出力先ディレクトリを指定
python call_tree_visualizer.py call-tree.tsv extract-sql --output-dir ./output/sqls
```

###### 出力ファイル名の規則

- 同一メソッド内で検出されたSQL文が1つ: 
  `<メソッド名>.sql`
- 同一メソッド内で検出されたSQL文が複数: 
  `<メソッド名>_<通番>.sql`

###### SQL整形

- `sqlparse` ライブラリなどを使用してSQL文をゆるく自動整形（構文解析はしない）
- キーワードは大文字化

#### analyze-tables : SQLファイルから使用テーブルを検出

SQLファイルから使用テーブルを検出し、標準出力にTSV形式で表示する。

このサブコマンドはextract-sqlサブコマンドで出力したSQLファイルを使用することを想定している。便宜上、引数に`call-tree.tsv`を指定するが、実際には使用しない。

```bash
$ python call_tree_visualizer.py call-tree.tsv analyze-tables --help
usage: call_tree_visualizer.py tsv_file analyze-tables [-h] [--sql-dir SQL_DIR] [--table-list TABLE_LIST]

options:
  -h, --help            show this help message and exit
  --sql-dir SQL_DIR     SQLディレクトリ (デフォルト: ./found_sql)
  --table-list TABLE_LIST
                        テーブルリストファイル (デフォルト: ./table_list.tsv)
```

```bash
python call_tree_visualizer.py call-tree.tsv analyze-tables

# SQLディレクトリとテーブルリストファイルを指定
python call_tree_visualizer.py call-tree.tsv analyze-tables --sql-dir ./output/sqls --table-list ./my_tables.tsv
```

###### テーブルリストファイルのフォーマット

```txt
<物理テーブル名><TAB><論理テーブル名><TAB><補足情報>
```

`<TAB>`: ハードタブ

例:

```txt
users<TAB>ユーザーマスタ<TAB>基本情報を管理
orders<TAB>注文テーブル<TAB>注文情報
order_details<TAB>注文明細<TAB>注文の詳細情報
```

###### 出力フォーマット

```txt
<SQLファイル名><TAB><物理テーブル名><TAB><論理テーブル名><TAB><補足情報>
```

`<TAB>`: ハードタブ

###### 使用例

```bash
# SQL抽出 → テーブル分析の一連の流れ
python call_tree_visualizer.py call-tree.tsv extract-sql
python call_tree_visualizer.py call-tree.tsv analyze-tables > table_usage.tsv

# 出力結果をExcel貼り付け用にコピー (Windows)
python call_tree_visualizer.py call-tree.tsv analyze-tables | clip
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

### 除外ルールファイルについて

--exclusion-fileオプションで除外ルールファイルを指定することで、特定のクラスやメソッドを呼び出しツリーから除外できる。

###### 除外ルールファイルのフォーマット

`exclusion_rules.txt`というファイル（デフォルト）を作成し、各行に以下の形式で記述する：

```txt
<クラス名 or メソッド名><TAB><I|E>
```

`<TAB>` : ハードタブ

- **I (Include) モード**: 対象自体を除外（そのメソッド/クラスおよび配下のツリー全体を除外）
- **E (Exclude) モード**: 対象は出力するが、配下の呼び出しを除外（そのメソッド/クラスまでは出力するが、それ以降の展開をスキップ）

###### 除外ルールの設定例

```txt
# Iモード: INFOログ出力を除外
org.slf4j.Logger#info(String)<TAB>I

# Iモード: ログ出力関連を完全に除外
org.slf4j.Logger<TAB>I

# Iモード: クラスを問わず、debug(String)を除外
debug(String)<TAB>I

# Eモード: com.example.Foo, com.example.Bar#method(Object) は出力するが、その配下は展開しない
com.example.Foo<TAB>E
com.example.Bar#method(Object)<TAB>E
```

###### 除外ルールファイルの使用例

```bash
# デフォルトの除外ファイル(exclusion_rules.txt)を使用
python call_tree_visualizer.py call-tree.tsv forward "$METHOD"

# カスタム除外ファイルを指定
python call_tree_visualizer.py call-tree.tsv --exclusion-file my_exclusions.txt forward "$METHOD"
```
