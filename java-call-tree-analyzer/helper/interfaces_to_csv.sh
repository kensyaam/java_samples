#!/bin/bash
# ============================================================
# analyzed_result.json の interfaces セクションを CSV/TSV に変換するスクリプト
# ============================================================
# 使用方法:
#   ./interfaces_to_csv.sh [input.json] [output_basename]
#
# 引数:
#   input.json       : analyzed_result.json (デフォルト: analyzed_result.json)
#   output_basename  : 出力ファイルのベース名（省略時はinterface_list）
#
# 出力:
#   <output_basename>.csv : CSV形式のファイル
#   <output_basename>.tsv : TSV形式のファイル
#
# 出力項目:
#   - interfaceName, javadoc, annotations, superInterfaces, hitWords
# ============================================================

set -e

# 引数処理（両方オプショナル）
INPUT_FILE="${1:-analyzed_result.json}"
OUTPUT_BASENAME="${2:-interface_list}"

# 入力ファイルの存在チェック
if [ ! -f "$INPUT_FILE" ]; then
    echo "エラー: ファイルが見つかりません: $INPUT_FILE"
    exit 1
fi

# jqコマンドの存在チェック
if ! command -v jq &> /dev/null; then
    echo "エラー: jq コマンドが必要です。" >&2
    echo "  Windowsではscoopを使ってインストール可能です。" >&2
    echo "  scoopがインストールされていない場合は、https://scoop.sh/ を参照してインストールしてください。" >&2
    echo "  scoopでインストール: scoop install jq" >&2
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
echo '"interfaceName","javadoc","annotations","superInterfaces","hitWords"' > "$OUTPUT_CSV"
# interfaces の場合
jq -r '.interfaces[] | [
    .interfaceName // "",
    .javadoc // "",
    ((.annotations // []) | join(",")),
    ((.superInterfaces // []) | join(",")),
    ((.hitWords // []) | join(","))
] | @csv' "$INPUT_FILE" >> "$OUTPUT_CSV"

# Shift-JISに変換
iconv -f UTF-8 -t SHIFT-JIS "$OUTPUT_CSV" > "${OUTPUT_CSV}.tmp" && mv "${OUTPUT_CSV}.tmp" "$OUTPUT_CSV"

# TSV形式で出力（タブ区切り）
OUTPUT_TSV="${OUTPUT_BASENAME}.tsv"
echo "TSV出力: $OUTPUT_TSV"

# ヘッダー行
echo -e "interfaceName\tjavadoc\tannotations\tsuperInterfaces\thitWords" > "$OUTPUT_TSV"
# interfaces の場合
jq -r '.interfaces[] | [
    .interfaceName // "",
    (.javadoc // "" | gsub("\t"; " ") | gsub("\n"; " ")),
    ((.annotations // []) | join(",") | gsub("\t"; " ")),
    ((.superInterfaces // []) | join(",")),
    ((.hitWords // []) | join(","))
] | @tsv' "$INPUT_FILE" >> "$OUTPUT_TSV"

# Shift-JISに変換
iconv -f UTF-8 -t SHIFT-JIS "$OUTPUT_TSV" > "${OUTPUT_TSV}.tmp" && mv "${OUTPUT_TSV}.tmp" "$OUTPUT_TSV"

echo "完了!"
echo "  CSV: $OUTPUT_CSV"
echo "  TSV: $OUTPUT_TSV"
