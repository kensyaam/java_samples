#!/bin/bash
# ============================================================
# analyzed_result.json の methods セクションを CSV/TSV に変換するスクリプト
# ============================================================
# 使用方法:
#   ./methods_to_csv.sh [input.json] [output_basename]
#
# 引数:
#   input.json       : CallTreeAnalyzerで出力したJSONファイル (デフォルト: analyzed_result.json)
#   output_basename  : 出力ファイルのベース名（省略時はfunc_listを使用）
#
# 出力:
#   <output_basename>.csv : CSV形式のファイル
#   <output_basename>.tsv : TSV形式のファイル
#
# 出力項目:
#   - method        : メソッドシグネチャ（完全修飾名）
#   - package       : パッケージ名
#   - className     : クラス名（シンプル名）
#   - methodName    : メソッド名（シンプル名）
#   - visibility    : 可視性 (public/protected/private)
#   - isAbstract    : 抽象メソッドかどうか
#   - isEntryPoint  : エントリーポイント候補かどうか
#   - javadoc       : メソッドのJavadoc要約
#   - annotations   : メソッドアノテーション（カンマ区切り）
#   - sqlStatements : SQL文（複数ある場合は " ||| " 区切り）
#   - hitWords      : 検出ワード（複数ある場合はカンマ区切り）
# ============================================================

set -e

# 引数処理（両方オプショナル）
INPUT_FILE="${1:-analyzed_result.json}"
OUTPUT_BASENAME="${2:-func_list}"

# 入力ファイルの存在チェック
if [ ! -f "$INPUT_FILE" ]; then
    echo "エラー: ファイルが見つかりません: $INPUT_FILE"
    exit 1
fi

# jqコマンドの存在チェック
if ! command -v jq &> /dev/null; then
    echo "エラー: jq コマンドが必要です。Windowsではscoopを使ってインストール可能です。"
    echo "  scoopがインストールされていない場合は、https://scoop.sh/ を参照してインストールしてください。"
    echo "  その後、scoop install jq を実行してください。"
    exit 1
fi

# iconvコマンドの存在チェック
if ! command -v iconv &> /dev/null; then
    echo "エラー: iconv コマンドが必要です。" >&2
    exit 1
fi

echo "処理中: $INPUT_FILE"

# CSV形式で出力（カンマ区切り）
OUTPUT_CSV="${OUTPUT_BASENAME}.csv"
echo "CSV出力: $OUTPUT_CSV"

# ヘッダー行
echo '"method","package","className","methodName","visibility","isAbstract","isEntryPoint","javadoc","annotations","sqlStatements","hitWords","httpMethod","uri"' > "$OUTPUT_CSV"

# jq でデータを抽出してCSVに変換（methodでソート）
# 完全修飾名 (例: com.example.MyClass#myMethod(String, int)) から
# パッケージ名、クラス名、メソッド名を抽出
jq -r '.methods | sort_by(.method) | .[] | 
    # メソッドシグネチャから「(」の前の部分を取得
    (.method // "" | split("(")[0]) as $fqn |
    # 「#」でクラス部分とメソッド名を分割
    ($fqn | split("#")) as $classAndMethod |
    # メソッド名は「#」の後の部分
    (if ($classAndMethod | length) >= 2 then $classAndMethod[1] else "" end) as $methodName |
    # クラス完全修飾名は「#」の前の部分
    ($classAndMethod[0] // "") as $fqClassName |
    # ドットで分割
    ($fqClassName | split(".")) as $parts |
    # クラス名は最後の要素
    ($parts | last // "") as $className |
    # パッケージ名は最後の1つを除いた部分を結合
    (if ($parts | length) >= 2 then ($parts[0:-1] | join(".")) else "" end) as $package |
[
    .method // "",
    $package,
    $className,
    $methodName,
    .visibility // "",
    (.isAbstract // false | tostring),
    (.isEntryPoint // false | tostring),
    .javadoc // "",
    ((.annotations // []) | join(",")),
    (if .sqlStatements then (.sqlStatements | join(" ||| ")) else "" end),
    (if .hitWords then (.hitWords | join(",")) else "" end),
    (if .httpCalls then (.httpCalls | map(.httpMethod) | join(", ")) else "" end),
    (if .httpCalls then (.httpCalls | map(.uri) | join(", ")) else "" end)
] | @csv' "$INPUT_FILE" >> "$OUTPUT_CSV"

# cp932に変換
iconv -f UTF-8 -t cp932 "$OUTPUT_CSV" > "${OUTPUT_CSV}.tmp" && mv "${OUTPUT_CSV}.tmp" "$OUTPUT_CSV"

# TSV形式で出力（タブ区切り）
OUTPUT_TSV="${OUTPUT_BASENAME}.tsv"
echo "TSV出力: $OUTPUT_TSV"

# ヘッダー行
echo -e "method\tpackage\tclassName\tmethodName\tvisibility\tisAbstract\tisEntryPoint\tjavadoc\tannotations\tsqlStatements\thitWords\thttpMethod\turi" > "$OUTPUT_TSV"

# jq でデータを抽出してTSVに変換（methodでソート）
# 完全修飾名から パッケージ名、クラス名、メソッド名を抽出
jq -r '.methods | sort_by(.method) | .[] | 
    # メソッドシグネチャから「(」の前の部分を取得
    (.method // "" | split("(")[0]) as $fqn |
    # 「#」でクラス部分とメソッド名を分割
    ($fqn | split("#")) as $classAndMethod |
    # メソッド名は「#」の後の部分
    (if ($classAndMethod | length) >= 2 then $classAndMethod[1] else "" end) as $methodName |
    # クラス完全修飾名は「#」の前の部分
    ($classAndMethod[0] // "") as $fqClassName |
    # ドットで分割
    ($fqClassName | split(".")) as $parts |
    # クラス名は最後の要素
    ($parts | last // "") as $className |
    # パッケージ名は最後の1つを除いた部分を結合
    (if ($parts | length) >= 2 then ($parts[0:-1] | join(".")) else "" end) as $package |
[
    .method // "",
    $package,
    $className,
    $methodName,
    .visibility // "",
    (.isAbstract // false | tostring),
    (.isEntryPoint // false | tostring),
    (.javadoc // "" | gsub("\t"; " ") | gsub("\n"; " ")),
    ((.annotations // []) | join(",") | gsub("\t"; " ")),
    (if .sqlStatements then (.sqlStatements | join(" ||| ") | gsub("\t"; " ") | gsub("\n"; " ")) else "" end),
    (if .hitWords then (.hitWords | join(",")) else "" end),
    (if .httpCalls then (.httpCalls | map(.httpMethod) | join(", ")) else "" end),
    (if .httpCalls then (.httpCalls | map(.uri) | join(", ") | gsub("\t"; " ")) else "" end)
] | @tsv' "$INPUT_FILE" >> "$OUTPUT_TSV"

# cp932に変換
iconv -f UTF-8 -t cp932 "$OUTPUT_TSV" > "${OUTPUT_TSV}.tmp" && mv "${OUTPUT_TSV}.tmp" "$OUTPUT_TSV"

echo "完了!"
echo "  CSV: $OUTPUT_CSV"
echo "  TSV: $OUTPUT_TSV"

