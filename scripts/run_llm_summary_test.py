# GijiMemo LLM Summary Accuracy Test
#
# Usage:
#   1. With API key:  python run_llm_summary_test.py --api-key sk-xxx --provider openai
#   2. With Ollama:   python run_llm_summary_test.py --provider ollama --ollama-url http://localhost:11434
#   3. Dry-run:       python run_llm_summary_test.py --dry-run  (tests the comparison logic only)
#
# The script:
#   1. Loads the reference meeting text from test_audio/REFERENCE_TEXT.md
#   2. Loads the expected summary from test_audio/EXPECTED_SUMMARY.md
#   3. Sends the meeting text to the LLM with the app's prompt template
#   4. Compares the LLM output with the expected summary using key-point matching
#   5. Reports accuracy percentage (target: >= 80%)

import sys
import os
import re
import json
import argparse
from pathlib import Path
from difflib import SequenceMatcher

SCRIPTS_DIR = Path(__file__).parent
TEST_DIR = SCRIPTS_DIR / "test_audio"
REFERENCE_FILE = TEST_DIR / "REFERENCE_TEXT.md"
EXPECTED_SUMMARY_FILE = TEST_DIR / "EXPECTED_SUMMARY.md"

# The app's default prompt template
PROMPT_TEMPLATE = """以下の会議録音を文字起こしし、以下の構造で Markdown 議事録を出力してください：

# 会議のテーマ
（内容から自動抽出）

## 参加者
（発言者を識別）

## 議題と討論
- 議題 1：... 発言者 A の意見...
- 議題 2：...

## 決定事項
- 決定 1：...

## アクションアイテム
- [ ] 担当者：事項（期限）"""


def load_texts():
    """Load reference and expected texts."""
    with open(REFERENCE_FILE, 'r', encoding='utf-8') as f:
        ref_text = f.read()

    with open(EXPECTED_SUMMARY_FILE, 'r', encoding='utf-8') as f:
        expected_summary = f.read()

    # Extract meeting scenario text (section 3)
    sections = ref_text.split('##')
    meeting_text = ""
    for section in sections:
        if '3. scenario_meeting' in section:
            # Get the text after the title line
            lines = section.strip().split('\n')[1:]
            meeting_text = '\n'.join(lines).strip()
            break

    return meeting_text, expected_summary


def call_llm_api(meeting_text: str, api_key: str = None, provider: str = "openai",
                 model: str = None, ollama_url: str = None) -> str:
    """Call LLM API to summarize the meeting text."""
    import httpx

    prompt = f"{PROMPT_TEMPLATE}\n\n{meeting_text}"

    if provider == "openai":
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        }
        payload = {
            "model": model or "gpt-4o-mini",
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.3,
            "stream": False
        }
        response = httpx.post(
            "https://api.openai.com/v1/chat/completions",
            headers=headers,
            json=payload,
            timeout=120
        )
        response.raise_for_status()
        data = response.json()
        return data["choices"][0]["message"]["content"]

    elif provider == "ollama":
        payload = {
            "model": model or "llama3.1",
            "messages": [{"role": "user", "content": prompt}],
            "stream": False,
            "options": {"temperature": 0.3}
        }
        base_url = ollama_url or "http://localhost:11434"
        response = httpx.post(
            f"{base_url}/v1/chat/completions",
            json=payload,
            timeout=300
        )
        response.raise_for_status()
        data = response.json()
        return data["message"]["content"]

    elif provider == "deepseek":
        headers = {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json"
        }
        payload = {
            "model": model or "deepseek-chat",
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0.3,
            "stream": False
        }
        response = httpx.post(
            "https://api.deepseek.com/v1/chat/completions",
            headers=headers,
            json=payload,
            timeout=120
        )
        response.raise_for_status()
        data = response.json()
        return data["choices"][0]["message"]["content"]

    else:
        raise ValueError(f"Unknown provider: {provider}")


def extract_key_points(text: str) -> list:
    """Extract key semantic points from a summary text.

    Returns a list of normalized key statement strings.
    """
    lines = text.strip().split('\n')
    points = []
    current_section = ""

    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue

        # Track section headers
        if stripped.startswith('#') and not stripped.startswith('# '):
            current_section = stripped.lstrip('#').strip()
            points.append(f"[section] {stripped}")
            continue

        # Extract list items (with or without markdown bullets)
        if stripped.startswith('- '):
            content = stripped[2:]
        elif stripped.startswith('  - '):
            content = stripped[4:]
        elif stripped.startswith('1.') or stripped.startswith('2.') or stripped.startswith('3.'):
            content = stripped[3:].strip()
        elif stripped.startswith('- [ ] '):
            content = stripped[6:]
        elif stripped.startswith('* '):
            content = stripped[2:]
        else:
            continue

        points.append(content)

    return points


def calculate_summary_accuracy(expected_summary: str, llm_output: str) -> dict:
    """Calculate how well the LLM output matches the expected summary."""
    expected_points = extract_key_points(expected_summary)
    llm_points = extract_key_points(llm_output)

    if not expected_points:
        return {"accuracy": 0.0, "matched": 0, "total": 0, "details": "No expected points"}

    # Normalize each point
    def normalize(text):
        text = re.sub(r'[#\*_\-\[\]\(\)]', '', text)
        text = re.sub(r'\s+', '', text)
        return text.lower()

    norm_expected = [normalize(p) for p in expected_points if not p.startswith('[section]')]
    norm_llm = [normalize(p) for p in llm_points if not p.startswith('[section]')]

    # Match expected points against LLM points (fuzzy matching)
    matched = 0
    match_details = []

    for ep in norm_expected:
        best = 0
        for lp in norm_llm:
            sm = SequenceMatcher(None, ep, lp)
            ratio = sm.ratio()
            # Also check if one contains the other
            if ep in lp or lp in ep:
                ratio = max(ratio, 0.9)
            best = max(best, ratio)

        is_matched = best >= 0.6
        if is_matched:
            matched += 1
        match_details.append({
            "point": ep[:50],
            "best_match": best,
            "matched": is_matched
        })

    accuracy = (matched / len(norm_expected)) * 100

    return {
        "accuracy": accuracy,
        "matched": matched,
        "total": len(norm_expected),
        "details": match_details,
        "llm_points_count": len(norm_llm),
        "expected_points_count": len(norm_expected)
    }


def main():
    parser = argparse.ArgumentParser(description="GijiMemo LLM Summary Accuracy Test")
    parser.add_argument("--api-key", help="API key for the LLM provider")
    parser.add_argument("--provider", default="openai", choices=["openai", "ollama", "deepseek"],
                        help="LLM provider")
    parser.add_argument("--model", help="Model name (default: provider-specific)")
    parser.add_argument("--ollama-url", default="http://localhost:11434",
                        help="Ollama base URL")
    parser.add_argument("--dry-run", action="store_true",
                        help="Skip actual LLM call, use expected summary as LLM output (tests comparison logic)")
    args = parser.parse_args()

    print("=" * 60)
    print("GijiMemo LLM Summary Accuracy Test")
    print("=" * 60)

    # Load texts
    meeting_text, expected_summary = load_texts()
    print(f"\nReference meeting text: {len(meeting_text)} chars")
    print(f"Expected summary: {len(expected_summary)} chars")

    # Get LLM output
    if args.dry_run:
        print("\nDRY RUN: Using expected summary as LLM output (testing comparison logic)")
        llm_output = expected_summary
    elif not args.api_key and args.provider != "ollama":
        print(f"\nERROR: --api-key required for provider '{args.provider}'")
        print("Use --dry-run to test the comparison logic, or --provider ollama for local LLM")
        sys.exit(1)
    else:
        print(f"\nCalling LLM ({args.provider})...")
        try:
            llm_output = call_llm_api(
                meeting_text=meeting_text,
                api_key=args.api_key,
                provider=args.provider,
                model=args.model,
                ollama_url=args.ollama_url
            )
        except Exception as e:
            print(f"ERROR calling LLM: {e}")
            sys.exit(1)

    # Calculate accuracy
    result = calculate_summary_accuracy(expected_summary, llm_output)

    print(f"\n{'=' * 60}")
    print(f"Summary Accuracy Results")
    print(f"{'=' * 60}")
    print(f"Expected key points: {result['expected_points_count']}")
    print(f"LLM output key points: {result['llm_points_count']}")
    print(f"Matched: {result['matched']}/{result['total']}")
    print(f"Accuracy: {result['accuracy']:.2f}%")

    if result['accuracy'] >= 95:
        print(f"VERDICT: EXCELLENT (>=95%)")
    elif result['accuracy'] >= 80:
        print(f"VERDICT: PASS (>=80%)")
    else:
        print(f"VERDICT: FAIL (<80%)")

    # Show match details for low-scoring points
    if result.get('details'):
        low_matches = [d for d in result['details'] if not d.get('matched', True)]
        if low_matches:
            print(f"\nUnmatched points ({len(low_matches)}):")
            for d in low_matches[:10]:
                print(f"  - '{d['point']}' (score: {d['best_match']:.2f})")


if __name__ == "__main__":
    main()
