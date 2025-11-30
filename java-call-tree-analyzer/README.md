# call-tree-analyzer

## ビルド

```bash
./gradlew build
```

```bash
# 基本的な使い方
./gradlew run --args="-s /path/to/your/source -o call-tree.tsv"

# 複数のソースディレクトリを指定
./gradlew run --args="-s /path/to/src1,/path/to/src2 -o call-tree.tsv"

# 依存ライブラリのクラスパス、Spring設定XMLファイルのディレクトリを指定
./gradlew run --args="-s /path/to/source -cp /path/to/lib1.jar,/path/to/lib2.jar -xml /path/to/xmldir -o call-tree.tsv"

# JSON形式で出力
./gradlew run --args="-s /path/to/source -o output.json -f json"

# GraphML形式で出力（Gephi等で可視化）
./gradlew run --args="-s /path/to/source -o output.graphml -f graphml"

# 実行可能JAR作成、実行
./gradlew shadowJar
java -jar build/libs/call-tree-analyzer-1.0.0.jar -s /path/to/source -cp /path/to/libdir -xml /path/to/xmldir -o call-tree.tsv -d

# クラスパスが複数あり、かつ、Git Bashから実行する場合を考慮
LIB_DIR="/path/to/libdir1,/path/to/libdir2"
LIB_DIR=$([[ -n "$MSYSTEM" ]] && printf '%s' "$LIB_DIR" | tr ',' '\n' | cygpath -w -f - | paste -sd',' - || printf '%s' "$LIB_DIR")
java -jar build/libs/call-tree-analyzer-1.0.0.jar -s /path/to/source -cp "$LIB_DIR" -xml /path/to/xmldir -o call-tree.tsv -d

```

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

## 機能

### Javadoc の @inheritDoc サポート

メソッドのJavadocに `@inheritDoc` または `{@inheritDoc}` タグが含まれている場合、親クラスやインターフェースから自動的にJavadocを継承します。

- 親クラス → インターフェースの順で検索
- 見つかった最初のJavadocを使用
- 継承元も `@inheritDoc` を使用している場合は再帰的に解決

これにより、TSV出力の「メソッドJavadoc」列やPython可視化ツールの出力で、継承されたJavadocが表示されます。

## 出力形式について

### TSV出力（デフォルト）

Excelで開きやすく、タブ区切りで整形されています
列：呼び出し元メソッド、呼び出し元クラス、呼び出し元の親クラス、呼び出し先メソッド、呼び出し先クラス、呼び出し先の親クラス、SQL文、方向（Forward/Reverse）など

### JSON出力

Pythonなどでのプログラム処理に適しています
メソッドごとに呼び出し・被呼び出し関係が構造化されています

### GraphML出力

Gephi、yEd、Cytoscapeなどのグラフ可視化ツールで開けます
呼び出し関係を視覚的に確認できます

## 動作検証に使えそうなプロジェクト

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

## ツリー可視化

```bash
# ツリーをExcelにエクスポートする場合のみ
python -m venv .venv
source .venv/Scripts/activate
pip install openpyxl
pip install types-openpyxl

# SQL抽出機能を使う場合
pip install sqlparse
```

### SQL抽出・テーブル分析機能

#### SQL文の抽出

TSVファイルから検出されたSQL文を個別のSQLファイルとして出力します。

```bash
# 基本的な使い方（デフォルトでは ./found_sql に出力）
python call_tree_visualizer.py call-tree.tsv --extract-sql

# 出力先ディレクトリを指定
python call_tree_visualizer.py call-tree.tsv --extract-sql --sql-output-dir ./output/sqls
```

**出力ファイル名の規則:**
- 同一メソッド内で検出されたSQL文が1つ: `<メソッド名>.sql`
- 同一メソッド内で検出されたSQL文が複数: `<メソッド名>_<通番>.sql`

**SQL整形:**
- `sqlparse` ライブラリを使用してSQL文を自動整形
- キーワードの大文字化、適切なインデントと改行を適用
- 構文エラーがあっても処理を継続（エラーで落ちない設計）

#### テーブル使用状況の分析

出力されたSQLファイルから使用テーブルを検出し、標準出力に表示します。

```bash
# 基本的な使い方
python call_tree_visualizer.py call-tree.tsv --analyze-tables

# SQLディレクトリとテーブルリストファイルを指定
python call_tree_visualizer.py call-tree.tsv --analyze-tables --sql-dir ./output/sqls --table-list ./my_tables.tsv
```

**テーブルリストファイル (`table_list.tsv`) のフォーマット:**
```
<物理テーブル名><TAB><論理テーブル名><TAB><補足情報>
```

**例:**
```
users	ユーザーマスタ	基本情報を管理
orders	注文テーブル	注文情報
order_details	注文明細	注文の詳細情報
```

**出力フォーマット:**
```
<SQLファイル名><TAB><物理テーブル名><TAB><論理テーブル名><TAB><補足情報>
```

**使用例:**
```bash
# SQL抽出 → テーブル分析の一連の流れ
python call_tree_visualizer.py call-tree.tsv --extract-sql
python call_tree_visualizer.py call-tree.tsv --analyze-tables > table_usage.tsv
```


```bash
# エントリーポイントを見つける - 厳密モード（デフォルト）
python call_tree_visualizer.py call-tree.tsv --list

# エントリーポイントを見つける - 厳密モード（デフォルト）
# 呼び出し数で絞り込み
python call_tree_visualizer.py call-tree.tsv --list --no-strict --min-calls 5

# キーワードでメソッドを検索
python call_tree_visualizer.py call-tree.tsv --search "main"

# 呼び出しツリーを表示
python call_tree_visualizer.py call-tree.tsv --forward "com.example.Main#main(String[])"

# 逆引きツリー（誰がこのメソッドを呼んでいるか） - オーバーライド元も追跡（デフォルト）
python call_tree_visualizer.py call-tree.tsv --reverse "com.example.UserDaoImpl#save(User)"
# 逆引きツリー（誰がこのメソッドを呼んでいるか） - オーバーライド元を追跡しない
python call_tree_visualizer.py call-tree.tsv --reverse "com.example.UserDaoImpl#save(User)" --no-follow-override

#ファイルにエクスポート
# テキスト形式
python call_tree_visualizer.py call-tree.tsv --export "com.example.Main#main(String[])" tree.txt text

# Markdown形式
python call_tree_visualizer.py call-tree.tsv --export "com.example.Main#main(String[])" tree.md markdown

# HTML形式（ブラウザで開ける）
python call_tree_visualizer.py call-tree.tsv --export "com.example.Main#main(String[])" tree.html html

# 深度を指定
python call_tree_visualizer.py call-tree.tsv --forward "com.example.Main#main(String[])" --depth 5

# 除外ルールファイルを使用
python call_tree_visualizer.py call-tree.tsv --forward "com.example.Main#main(String[])" --exclusion-file my_exclusions.txt

# エントリーポイントの呼び出しツリーを一括出力
python call_tree_visualizer.py call-tree.tsv --list | grep -E "^[0-9]+\." | sed -E "s|^[0-9]+\. ||g" | while read -r line; do
  python call_tree_visualizer.py call-tree.tsv --forward "$line";
done
```

### 除外機能

特定のクラスやメソッドをツリーから除外できます。

#### 除外ルールファイルのフォーマット

`exclusion_rules.txt`というファイル（デフォルト）を作成し、各行に以下の形式で記述します：

```
<クラス名 or メソッド名><TAB><I|E>
```

- **Iモード**: 対象自体を除外（そのメソッド/クラスおよび配下のツリー全体を除外）
- **Eモード**: 対象は表示するが、配下の呼び出しを除外（そのメソッド/クラスまでは表示するが、それ以降の展開をスキップ）

#### 除外ルールの例

```
# Iモード: ログ出力関連を完全に除外
org.slf4j.Logger	I
log	I

# Eモード: java.util.List は表示するが、その配下は展開しない
java.util.List	E
java.util.ArrayList#add(Object)	E
```

#### 除外機能の使用例

```bash
# デフォルトの除外ファイル(exclusion_rules.txt)を使用
python call_tree_visualizer.py call-tree.tsv --forward "com.example.Main#main(String[])"

# カスタム除外ファイルを指定
python call_tree_visualizer.py call-tree.tsv --forward "com.example.Main#main(String[])" --exclusion-file my_exclusions.txt
```

```bash
$ python call_tree_visualizer.py
使い方:
  python call_tree_visualizer.py <TSVファイル> [オプション]

オプション:
  --list [--no-strict]  エントリーポイント候補を表示
                        デフォルトは厳密モード、--no-strictで緩和
  --search <keyword>  キーワードでメソッドを検索
  --forward <method>  指定メソッドからの呼び出しツリーを表示
  --reverse <method>  指定メソッドへの呼び出し元ツリーを表示
  --export <method> <o> [format]  ツリーをファイルにエクスポート
                      format: text, markdown, html (default: text)
  --export-excel <entry_points_file|- > <output_file>  ツリーをExcelにエクスポート
  --depth <n>         ツリーの最大深度 (default: 50)
  --min-calls <n>     エントリーポイントの最小呼び出し数 (default: 1)
  --exclusion-file <file>  除外ルールファイルのパス (default: exclusion_rules.txt)
  --no-follow-impl    実装クラス候補を追跡しない
  --no-follow-override  逆引き時にオーバーライド元を追跡しない

除外ルールファイルのフォーマット:
  <クラス名 or メソッド名><TAB><I|E>
  I: 対象自体を除外
  E: 対象は表示するが、配下の呼び出しを除外

例:
  python call_tree_visualizer.py call-tree.tsv --list
  python call_tree_visualizer.py call-tree.tsv --list --no-strict --min-calls 5
  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Main#main(String[])'
  python call_tree_visualizer.py call-tree.tsv --reverse 'com.example.Service#process()'
  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Service#process()' --no-follow-impl
  python call_tree_visualizer.py call-tree.tsv --export 'com.example.Main#main(String[])' tree.html html
  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Main#main(String[])' --exclusion-file my_exclusions.txt
```
