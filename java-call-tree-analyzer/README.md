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

## 出力形式について

### TSV出力（デフォルト）

Excelで開きやすく、タブ区切りで整形されています
列：呼び出し元メソッド、呼び出し元クラス、親クラス、呼び出し先メソッド、呼び出し先クラス、SQL文、方向（Forward/Reverse）など

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

# エントリーポイントの呼び出しツリーを一括出力
python call_tree_visualizer.py call-tree.tsv --list | grep -E "^[0-9]+\." | sed -E "s|^[0-9]+\. ||g" | while read -r line; do
  python call_tree_visualizer.py call-tree.tsv --forward "$line";
done
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
  --export-excel <entry_points_file|- > <output_file>  ツリーをExcelにエクス 
ポート
  --depth <n>         ツリーの最大深度 (default: 50)
  --min-calls <n>     エントリーポイントの最小呼び出し数 (default: 1)
  --no-follow-impl    実装クラス候補を追跡しない
  --no-follow-override  逆引き時にオーバーライド元を追跡しない

例:
  python call_tree_visualizer.py call-tree.tsv --list
  python call_tree_visualizer.py call-tree.tsv --list --no-strict --min-calls 5
  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Main#main(String[])'
  python call_tree_visualizer.py call-tree.tsv --reverse 'com.example.Service#process()'
  python call_tree_visualizer.py call-tree.tsv --forward 'com.example.Service#process()' --no-follow-impl
  python call_tree_visualizer.py call-tree.tsv --export 'com.example.Main#main(String[])' tree.html html

```
