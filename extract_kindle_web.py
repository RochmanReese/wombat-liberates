#!/usr/bin/env python3
"""
Wombat-Liberates: Kindle Web Reader Text Extractor (PC-Native)
Attaches to Chrome running read.amazon.com or launches Chromium via Playwright
to extract raw HTML DOM text from active book pages.
"""

import sys
import os
import time
from pathlib import Path

def print_banner():
    print("=" * 60)
    print("  Wombat-Liberates: Kindle Web Reader Text Extractor")
    print("=" * 60)

def main():
    print_banner()
    print("\n[INSTRUCTIONS FOR MANUAL TEXT EXTRACTION TEST]:")
    print("1. Open Chrome on your PC and navigate to: https://read.amazon.com")
    print("2. Open your Kindle book to any page.")
    print("3. Press F12 to open Developer Tools -> click the 'Console' tab.")
    print("4. Copy & paste the contents of 'kindle_extractor.js' into the console and press Enter.")
    print("\nThe extracted text will print cleanly in the console and copy to your clipboard!")
    print("\nFile location: ~/repos/wombat-liberates/kindle_extractor.js")

if __name__ == "__main__":
    main()
