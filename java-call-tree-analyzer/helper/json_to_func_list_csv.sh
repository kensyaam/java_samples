#!/bin/bash
# ============================================================
# call-tree.json を CSV/TSV に変換するスクリプト
# CallTreeAnalyzerで出力したJSONファイルを指定項目でCSV/TSVに変換
# ============================================================
# 使用方法:
#   ./json_to_func_list_csv.sh <input.json> [output_basename]
#
# 引数:
#   input.json       : CallTreeAnalyzerで出力したJSONファイル
#   output_basename  : 出力ファイルのベース名（省略時はfunc_listを使用）
#
# 出力:
#   <output_basename>.csv : CSV形式のファイル
#   <output_basename>.tsv : TSV形式のファイル
#
# 出力項目:
#   - method        : メソッドシグネチャ
#   - visibility    : 可視性 (public/protected/private)
#   - isEntryPoint  : エントリーポイント候補かどうか
#   - javadoc       : メソッドのJavadoc要約
#   - sqlStatements : SQL文（複数ある場合は " ||| " 区切り）
#   - hitWords      : 検出ワード（複数ある場合はカンマ区切り）
# ============================================================

set -e

# 引数チェック
if [ $# -lt 1 ]; then
    echo "使用方法: $0 <input.json> [output_basename]"
    echo "例: $0 call-tree.json output"
    exit 1
fi

INPUT_FILE="$1"
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

echo "処理中: $INPUT_FILE"

# CSV形式で出力（カンマ区切り）
OUTPUT_CSV="${OUTPUT_BASENAME}.csv"
echo "CSV出力: $OUTPUT_CSV"

# ヘッダー行
echo '"method","visibility","isEntryPoint","javadoc","sqlStatements","hitWords"' > "$OUTPUT_CSV"

# jq でデータを抽出してCSVに変換
jq -r '.methods[] | [
    .method // "",
    .visibility // "",
    (.isEntryPoint // false | tostring),
    .javadoc // "",
    (if .sqlStatements then (.sqlStatements | join(" ||| ")) else "" end),
    (if .hitWords then (.hitWords | join(",")) else "" end)
] | @csv' "$INPUT_FILE" >> "$OUTPUT_CSV"

# TSV形式で出力（タブ区切り）
OUTPUT_TSV="${OUTPUT_BASENAME}.tsv"
echo "TSV出力: $OUTPUT_TSV"

# ヘッダー行
echo -e "method\tvisibility\tisEntryPoint\tjavadoc\tsqlStatements\thitWords" > "$OUTPUT_TSV"

# jq でデータを抽出してTSVに変換
jq -r '.methods[] | [
    .method // "",
    .visibility // "",
    (.isEntryPoint // false | tostring),
    (.javadoc // "" | gsub("\t"; " ") | gsub("\n"; " ")),
    (if .sqlStatements then (.sqlStatements | join(" ||| ") | gsub("\t"; " ") | gsub("\n"; " ")) else "" end),
    (if .hitWords then (.hitWords | join(",")) else "" end)
] | @tsv' "$INPUT_FILE" >> "$OUTPUT_TSV"

echo "完了!"
echo "  CSV: $OUTPUT_CSV"
echo "  TSV: $OUTPUT_TSV"
