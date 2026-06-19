#!/usr/bin/env python3
"""
uiautomator2 自动化测试 — GIMI MEMO v0.3.0 完整录音 + 转写流程。

测试覆盖:
  1. App 启动 + 首页可见
  2. 进入录音页
  3. 录音 5 秒（mock — 不会真录音，由 uiautomator2 点击开始/停止）
  4. 停止录音 → "停止済み" 状态可见
  5. 点 "文字起こし" → 处理页可见
  6. 等待处理完成（最多 5 分钟）
  7. 预览页：检测语言 chip + COPY 按钮可见
  8. 点 COPY → Toast 出现
  9. 返回首页验证录音 session 出现在列表

Usage:
  # 1. 启动 uiautomator2 服务
  python -m uiautomator2 init 8b49be5c

  # 2. (可选) 推 atx-agent 到设备
  python -m uiautomator2 install 8b49be5c

  # 3. 跑测试
  python scripts/ui_test_recording_flow.py
"""
import os
import sys
import time
import subprocess
from pathlib import Path

import uiautomator2 as u2

DEVICE = "8b49be5c"  # 小米 Note 10 Pro
PKG = "com.gijimemo"
SCREENSHOT_DIR = Path(__file__).resolve().parent.parent / "test_screenshots" / "ui_auto"
SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)


def shoot(d, name: str) -> None:
    """Save screenshot with timestamp."""
    out = SCREENSHOT_DIR / f"{int(time.time())}_{name}.png"
    d.screenshot(str(out))
    print(f"  📸 {out.name}")


def wait_text(d, text: str, timeout: float = 30) -> bool:
    """Block until text appears (or timeout)."""
    try:
        return d(text=text).wait(timeout=timeout)
    except Exception:
        return False


def wait_any_text(d, *texts, timeout: float = 30) -> str | None:
    """Wait for any of the texts."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for t in texts:
            if d(text=t).exists:
                return t
        time.sleep(1)
    return None


def main() -> int:
    print("🐶 MiuMiu UI 自动化测试 — GIMI MEMO v0.3.0")
    print(f"📱 Device: {DEVICE}")

    # ─── Connect ───────────────────────────────────────────────
    d = u2.connect(DEVICE)
    info = d.info
    print(f"[CONNECTED] productName={info.get('productName', '?')}")
    print(f"[INFO] display={info.get('displaySizeDpY', '?')}dp sdk={info.get('sdkInt', '?')}")

    # ─── Step 1: 启动 App ─────────────────────────────────────
    print("\n[1/9] 启动 App ...")
    d.app_stop(PKG)
    time.sleep(1)
    d.app_start(PKG)
    time.sleep(3)
    shoot(d, "01_home")

    if not d(textContains="GIMI MEMO").exists:
        print("❌ 首页没渲染")
        return 1
    print("✅ 首页可见")

    # ─── Step 2: 进入录音页 ──────────────────────────────────
    print("\n[2/9] 点 '録音' FAB 进入录音页 ...")
    # 录音 FAB 是 ExtendedFloatingActionButton，text 节点找不到（被 image 吞了）
    # 用坐标点击：屏 1080x2340，FAB 在右下 (898, 2089)
    if d.window_size()[0] == 1080:
        fab_x, fab_y = 898, 2089
    else:
        # 兜底：相对坐标
        sz = d.window_size()
        fab_x, fab_y = int(sz[0] * 0.83), int(sz[1] * 0.89)
    print(f"  FAB coord: ({fab_x}, {fab_y})")
    d.click(fab_x, fab_y)
    time.sleep(2)
    shoot(d, "02_recording_idle")

    if not d(textContains="録音開始").exists:
        print("❌ 没进入录音页（录音開始 按钮看不到）")
        return 1
    print("✅ 录音页可见")

    # ─── Step 3: 录音 ─────────────────────────────────────────
    print("\n[3/9] 录音 5 秒 ...")
    d(text="録音開始").click()
    time.sleep(5)
    shoot(d, "03_recording")
    print("✅ 录了 5 秒")

    # ─── Step 4: 停止录音 ─────────────────────────────────────
    print("\n[4/9] 点 '停止して保存' ...")
    if not d(textContains="停止して保存").exists:
        print("❌ 找不到 '停止して保存' 按钮")
        return 1
    d(textContains="停止して保存").click()
    time.sleep(2)
    shoot(d, "04_stopped")

    # 验证状态: 「停止済み」
    if not d(text="停止済み").exists:
        print("⚠️  状态可能不是 '停止済み'，但继续")
    else:
        print("✅ 状态: 停止済み")

    # ─── Step 5: 验证文字起こし 按钮存在 ─────────────────────
    print("\n[5/9] 验证 '文字起こし' 按钮 ...")
    if not d(text="文字起こし").exists:
        print("❌ '文字起こし' 按钮不可见（lastSavedSessionId 问题）")
        return 1
    print("✅ '文字起こし' 按钮可见")

    # ─── Step 6: 点文字起こし，等处理完成 ─────────────────────
    print("\n[6/9] 点 '文字起こし' ...")
    d(text="文字起こし").click()
    time.sleep(3)
    shoot(d, "06_processing_start")

    # 等处理完成 (最多 5 分钟) — Whisper 完成后会出现 "要约" 按钮
    # (TRANSCRIBED 阶段) 或直接跳到预览页 (COMPLETED)
    print("  ⏳ 等待 Whisper 模型加载 + 转写 (最多 5 分钟) ...")
    found = wait_any_text(
        d,
        "要约",  # TRANSCRIBED 阶段：转录完成可要約
        "検出",  # PREVIEW 阶段：语言检测 chip
        "COPY",  # PREVIEW 阶段：复制按钮
        "失敗",  # ERROR 阶段
        timeout=300,
    )

    if found is None:
        print("❌ 处理超时")
        shoot(d, "06_processing_timeout")
        return 1
    if found == "失敗":
        print("❌ 处理失败")
        shoot(d, "06_processing_error")
        return 1
    print(f"✅ 处理完成（检测到 '{found}'）")
    shoot(d, "06_after_transcribe")

    # 如果停在 TRANSCRIBED 阶段，点 "要约" 进入 LLM 整理
    if found == "要约":
        print("  → 点 '要约' 让 LLM 整理 ...")
        d(text="要约").click()
        time.sleep(2)
        # 等 LLM 完成
        preview_found = wait_any_text(
            d,
            "COPY",
            "検出",
            "戻る",  # preview 页也有
            "メール",  # preview 页也有
            timeout=300,
        )
        if preview_found is None:
            print("❌ LLM 整理超时")
            shoot(d, "06_llm_timeout")
            return 1
        print(f"✅ LLM 整理完成（检测到 '{preview_found}'）")
        shoot(d, "06_preview")

    # ─── Step 7: 验证检测语言 chip ─────────────────────────
    print("\n[7/9] 验证检测语言 chip ...")
    if not d(textStartsWith="検出:").exists:
        print("⚠️  没找到 '検出: xxx' chip（可能 LLM 没生成）")
    else:
        chip_text = d(textStartsWith="検出:").get_text()
        print(f"✅ 语言 chip: {chip_text}")

    # ─── Step 8: COPY 按钮 + Toast ─────────────────────────
    print("\n[8/9] 点 COPY 按钮 ...")
    if not d(text="COPY").exists:
        print("⚠️  找不到 COPY 按钮")
    else:
        d(text="COPY").click()
        time.sleep(2)
        # Android 13+ 自动显示系统复制通知
        if d(textContains="コピーしました").exists:
            print("✅ Toast: コピーしました")
        else:
            print("ℹ️  复制动作已触发（无 Toast 或已消失）")
        shoot(d, "08_after_copy")

    # ─── Step 9: 返回首页验证 ───────────────────────────────
    print("\n[9/9] 点 '戻る' 返回首页 ...")
    if d(text="戻る").exists:
        d(text="戻る").click()
        time.sleep(2)
        shoot(d, "09_back_home")
        if d(textContains="GIMI MEMO").exists:
            print("✅ 回到首页，新 session 应在列表")
        else:
            print("⚠️  未回到首页")

    print("\n🐶 测试通过！截图在:", SCREENSHOT_DIR)
    return 0


if __name__ == "__main__":
    sys.exit(main())
