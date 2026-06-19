# GijiMemo 文字起こし精度分析スクリプト
#
# Whisper の認識結果と参照テキストを比較し、一致率を計算する。
#
# 使い方:
#   python analyze_accuracy.py <認識結果.txt> <参照テキスト.md>
#
# 出力:
#   - 文字単位の一致率 (%)
#   - 単語/形態素単位の一致率 (%)
#   - WER (Word Error Rate)
#   - 差分サマリー

import sys
import re
from difflib import SequenceMatcher

def load_text(path: str) -> str:
    """テキストファイルを読み込む"""
    with open(path, 'r', encoding='utf-8') as f:
        return f.read().strip()

def normalize(text: str) -> str:
    """比較用にテキストを正規化する"""
    # マークダウン除去（簡易）
    text = re.sub(r'^#.*$', '', text, flags=re.MULTILINE)
    text = re.sub(r'\*\*(.+?)\*\*', r'\1', text)
    text = re.sub(r'[─\-\*_#\[\]()>|]', '', text)
    # 空白正規化
    text = re.sub(r'\s+', '', text)  # 全ての空白を除去（日本語の文字単位比較用）
    return text

def normalize_words(text: str) -> list:
    """単語単位の分割（日本語は文字、英語は単語）"""
    # 日本語は文字単位、英数字は単語単位で分割
    words = []
    for char in text:
        if re.match(r'[぀-ゟ゠-ヿ一-鿿＀-￯]', char):
            words.append(char)
        elif re.match(r'[a-zA-Z0-9]', char):
            words.append(char.lower())
        elif char in '.,!?。、！？':
            words.append(char)
    return words

def calculate_char_accuracy(reference: str, hypothesis: str) -> float:
    """文字単位の一致率を計算"""
    ref_norm = normalize(reference)
    hyp_norm = normalize(hypothesis)

    if not ref_norm:
        return 0.0 if hyp_norm else 100.0

    matcher = SequenceMatcher(None, ref_norm, hyp_norm)
    matches = sum(m.size for m in matcher.get_matching_blocks() if m.size > 0)
    total = max(len(ref_norm), len(hyp_norm))

    return (matches / total) * 100

def calculate_wer(reference: str, hypothesis: str) -> float:
    """WER (Word Error Rate) を計算（簡易版）"""
    ref_norm = normalize(reference)
    hyp_norm = normalize(hypothesis)

    if not ref_norm:
        return 0.0

    ref_chars = list(ref_norm)
    hyp_chars = list(hyp_norm)

    # Levenshtein distance
    m, n = len(ref_chars), len(hyp_chars)
    dp = [[0] * (n + 1) for _ in range(m + 1)]

    for i in range(m + 1):
        dp[i][0] = i
    for j in range(n + 1):
        dp[0][j] = j

    for i in range(1, m + 1):
        for j in range(1, n + 1):
            cost = 0 if ref_chars[i-1] == hyp_chars[j-1] else 1
            dp[i][j] = min(
                dp[i-1][j] + 1,       # deletion
                dp[i][j-1] + 1,       # insertion
                dp[i-1][j-1] + cost   # substitution
            )

    return (dp[m][n] / m) * 100

def print_diff(reference: str, hypothesis: str):
    """差分を表示"""
    ref_norm = normalize(reference)
    hyp_norm = normalize(hypothesis)

    matcher = SequenceMatcher(None, ref_norm, hyp_norm)

    print("\n=== 差分分析 ===")
    for op, i1, i2, j1, j2 in matcher.get_opcodes():
        if op == 'equal':
            continue
        ref_seg = ref_norm[i1:i2] if i1 < i2 else '(なし)'
        hyp_seg = hyp_norm[j1:j2] if j1 < j2 else '(なし)'

        if op == 'replace':
            print(f"  [置換] 正解: '{ref_seg}' → 認識: '{hyp_seg}'")
        elif op == 'delete':
            print(f"  [削除] 正解にあった '{ref_seg}' が認識から欠落")
        elif op == 'insert':
            print(f"  [挿入] 認識に余分な '{hyp_seg}' がある")

def main():
    if len(sys.argv) < 3:
        print("使い方: python analyze_accuracy.py <認識結果.txt> <参照テキスト.md>")
        print("")
        print("テスト用: 同梱の参照テキストと期待要約を使用")
        print("  python analyze_accuracy.py output/transcript.txt scripts/test_audio/REFERENCE_TEXT.md")
        sys.exit(1)

    hypothesis = load_text(sys.argv[1])
    reference = load_text(sys.argv[2])

    print("=== GijiMemo 文字起こし精度分析 ===\n")

    char_acc = calculate_char_accuracy(reference, hypothesis)
    wer = calculate_wer(reference, hypothesis)

    print(f"参照文字数: {len(normalize(reference))}")
    print(f"認識文字数: {len(normalize(hypothesis))}")
    print(f"文字一致率: {char_acc:.1f}%")
    print(f"WER (文字誤り率): {wer:.1f}%")
    print(f"判定: ", end="")
    if char_acc >= 95:
        print("✅ 優秀 (95%以上)")
    elif char_acc >= 80:
        print("✅ 合格 (80%以上)")
    else:
        print("❌ 不合格 (80%未満)")

    # 差分表示（文字一致率が低い場合のみ）
    if char_acc < 95:
        print_diff(reference, hypothesis)

    print("\n=== 精度目標 ===")
    print(f"  目標: {80}%")
    print(f"  結果: {char_acc:.1f}%")
    print(f"  {'✅ 達成' if char_acc >= 80 else '❌ 未達成'}")


def analyze_summary_accuracy(reference: str, hypothesis: str) -> float:
    """要約一致率の分析"""
    # キーポイント抽出
    ref_points = set()
    for line in reference.split('\n'):
        line = line.strip()
        if line and not line.startswith('#') and not line.startswith('-') and not line.startswith('>'):
            ref_points.add(normalize(line))

    hyp_points = set()
    for line in hypothesis.split('\n'):
        line = line.strip()
        if line and not line.startswith('#') and not line.startswith('-') and not line.startswith('>'):
            hyp_points.add(normalize(line))

    if not ref_points:
        return 0.0

    # 参照ポイントのうち、認識でも見つかった割合
    found = sum(1 for rp in ref_points if any(rp in hp or hp in rp for hp in hyp_points))
    return (found / len(ref_points)) * 100


if __name__ == "__main__":
    main()
