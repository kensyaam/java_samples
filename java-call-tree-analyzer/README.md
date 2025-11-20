# call-tree-analyzer

## ビルド

```bash
./gradlew build
```

```bash
# 基本的な使い方
./gradlew run --args="-s /path/to/your/source -o output.tsv"

# 複数のソースディレクトリを指定
./gradlew run --args="-s /path/to/src1,/path/to/src2 -o output.tsv"

# 依存ライブラリのクラスパスを指定
./gradlew run --args="-s /path/to/source -cp /path/to/lib1.jar,/path/to/lib2.jar -o output.tsv"

# JSON形式で出力
./gradlew run --args="-s /path/to/source -o output.json -f json"

# GraphML形式で出力（Gephi等で可視化）
./gradlew run --args="-s /path/to/source -o output.graphml -f graphml"

# 実行可能JAR作成、実行
./gradlew shadowJar
java -jar build/libs/call-tree-analyzer-1.0.0.jar -s /path/to/source -o output.tsv
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
java -jar call-tree-analyzer-1.0.0.jar -s ../../ext/spring-framework-petclinic/src/main -cp ../../ext/spring-framework-petclinic/target/dependency -o dist/output.tsv
```

## ツリー可視化

```bash
# エントリーポイントを見つける
python call_tree_visualizer.py call-tree.tsv --list

# 厳密モード
python call_tree_visualizer.py call-tree.tsv --list --strict
# 呼び出し数で絞り込み
python call_tree_visualizer.py call-tree.tsv --list --min-calls 5
# 両方の条件を組み合わせ
python call_tree_visualizer.py call-tree.tsv --list --strict --min-calls 3

# キーワードでメソッドを検索
python call_tree_visualizer.py call-tree.tsv --search "main"

# 呼び出しツリーを表示
python call_tree_visualizer.py call-tree.tsv --forward "com.example.Main#main(String[])"

# 逆引きツリー（誰がこのメソッドを呼んでいるか）
python call_tree_visualizer.py call-tree.tsv --reverse "com.example.Service#process()"

#ファイルにエクスポート
# テキスト形式
python call_tree_visualizer.py call-tree.tsv --export "com.example.Main#main(String[])" tree.txt text

# Markdown形式
python call_tree_visualizer.py call-tree.tsv --export "com.example.Main#main(String[])" tree.md markdown

# HTML形式（ブラウザで開ける）
python call_tree_visualizer.py call-tree.tsv --export "com.example.Main#main(String[])" tree.html html

# 深度を指定
python call_tree_visualizer.py call-tree.tsv --forward "com.example.Main#main(String[])" --depth 5

```
