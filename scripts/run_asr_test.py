# GijiMemo ASR Accuracy Test
# Runs faster-whisper on test MP3s and compares with reference text.

import sys
import os
from pathlib import Path

# Add scripts dir to path
scripts_dir = Path(__file__).parent
sys.path.append(str(scripts_dir))

# Import accuracy analyzer
from analyze_accuracy import calculate_char_accuracy, calculate_wer, print_diff

TEST_DIR = scripts_dir / "test_audio"
REFERENCE_FILE = TEST_DIR / "REFERENCE_TEXT.md"

def transcribe_with_whisper(mp3_path: str, model_size: str = "base") -> str:
    """Transcribe audio file using faster-whisper."""
    from faster_whisper import WhisperModel

    print(f"Loading whisper model '{model_size}'...")
    model = WhisperModel(model_size, device="cpu", compute_type="int8")

    print(f"Transcribing: {mp3_path}")
    segments, info = model.transcribe(mp3_path, language="ja", beam_size=5)

    full_text = ""
    for segment in segments:
        full_text += segment.text

    print(f"Transcription complete: {len(full_text)} chars")
    return full_text.strip()

def main():
    if len(sys.argv) > 1:
        test_file = sys.argv[1]
    else:
        # Default: use the medium test file
        test_file = str(TEST_DIR / "recitation_short.mp3")

    if not os.path.exists(test_file):
        print(f"Test file not found: {test_file}")
        sys.exit(1)

    if not os.path.exists(REFERENCE_FILE):
        print(f"Reference file not found: {REFERENCE_FILE}")
        sys.exit(1)

    # Load reference text
    with open(REFERENCE_FILE, 'r', encoding='utf-8') as f:
        reference_text = f.read()

    # Extract the relevant section from reference
    test_filename = os.path.basename(test_file)
    sections = reference_text.split('##')

    # Find the matching section
    section_map = {
        'recitation_short.mp3': '1. recitation_short',
        'recitation_medium.mp3': '2. recitation_medium',
        'scenario_meeting.mp3': '3. scenario_meeting',
    }

    target_section = section_map.get(test_filename)
    if not target_section:
        print(f"Unknown test file: {test_filename}")
        sys.exit(1)

    print(f"\n{'='*60}")
    print(f"GijiMemo ASR Accuracy Test")
    print(f"{'='*60}")
    print(f"Test file: {test_file}")
    print(f"Section: {target_section}")

    # Run transcription
    hypothesis = transcribe_with_whisper(test_file)

    print(f"\nTranscription result:")
    print(f"  {hypothesis[:200]}..." if len(hypothesis) > 200 else f"  {hypothesis}")

    # Find the relevant reference section text
    ref_lines = []
    in_section = False
    for line in reference_text.split('\n'):
        if f'## {target_section}' in line:
            in_section = True
            continue
        if in_section:
            if line.startswith('## ') and not line.startswith(f'## {target_section}'):
                break
            ref_lines.append(line)

    reference = '\n'.join(ref_lines).strip()

    print(f"\nReference text:")
    print(f"  {reference[:200]}..." if len(reference) > 200 else f"  {reference}")

    # Calculate accuracy
    char_acc = calculate_char_accuracy(reference, hypothesis)
    wer = calculate_wer(reference, hypothesis)

    print(f"\n{'='*60}")
    print(f"Accuracy Results")
    print(f"{'='*60}")
    print(f"Reference chars: {len(''.join(filter(str.isalnum, reference)))}")
    print(f"Hypothesis chars: {len(''.join(filter(str.isalnum, hypothesis)))}")
    print(f"Char accuracy: {char_acc:.2f}%")
    print(f"WER: {wer:.2f}%")

    # Save transcription
    output_path = str(TEST_DIR / f"transcript_{test_filename.replace('.mp3', '.txt')}")
    with open(output_path, 'w', encoding='utf-8') as f:
        f.write(hypothesis)
    print(f"\nFull transcription saved: {output_path}")

    # Print verdict
    print(f"\n{'='*60}")
    if char_acc >= 95:
        print(f"VERDICT: ✅ EXCELLENT (≥95%)")
    elif char_acc >= 80:
        print(f"VERDICT: ✅ PASS (≥80%)")
    else:
        print(f"VERDICT: ❌ FAIL (<80%)")

    if char_acc < 95:
        print("\nDifferences found:")
        print_diff(reference, hypothesis)

    return char_acc, wer

if __name__ == "__main__":
    main()
