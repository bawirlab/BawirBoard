#!/usr/bin/env python3

import subprocess
import os
import shutil
import sys

# Configuration
USERNAME = "kdrnyzvg"
PASSWORD = ""
SOURCE_REPO = "BawirBoard"
TARGET_REPO = "test"

def run_command(command, description):
    """Execute shell command and handle errors"""
    print(f"▶ {description}...")
    try:
        result = subprocess.run(command, shell=True, check=True, capture_output=True, text=True)
        print(f"✓ {description} completed")
        return True
    except subprocess.CalledProcessError as e:
        print(f"✗ Error during {description}")
        print(f"  Error: {e.stderr}")
        return False

def main():
    print("=" * 60)
    print("Repository Copy Tool")
    print("=" * 60)
    
    # Step 1: Clone as mirror
    mirror_dir = f"{SOURCE_REPO}.git"
    
    if os.path.exists(mirror_dir):
        print(f"⚠ Removing existing {mirror_dir}...")
        shutil.rmtree(mirror_dir)
    
    clone_url = f"https://{USERNAME}:{PASSWORD}@github.com/{USERNAME}/{SOURCE_REPO}.git"
    clone_cmd = f"git clone --mirror {clone_url} {mirror_dir}"
    
    if not run_command(clone_cmd, "Cloning repository as mirror"):
        sys.exit(1)
    
    # Step 2: Push to new repository
    push_url = f"https://{USERNAME}:{PASSWORD}@github.com/{USERNAME}:{PASSWORD}@github.com/{USERNAME}/{TARGET_REPO}.git"
    push_cmd = f"cd {mirror_dir} && git push --mirror {push_url}"
    
    if not run_command(push_cmd, "Pushing to new repository"):
        # Cleanup on failure
        shutil.rmtree(mirror_dir)
        sys.exit(1)
    
    # Step 3: Cleanup
    print(f"\n▶ Cleaning up temporary files...")
    shutil.rmtree(mirror_dir)
    print("✓ Cleanup completed")
    
    print("\n" + "=" * 60)
    print("✓ Copy Complete!")
    print("=" * 60)
    print(f"✓ All content copied to: https://github.com/{USERNAME}/{TARGET_REPO}")
    print(f"✓ Branches: All copied")
    print(f"✓ History: All copied")
    print(f"✓ Tags: All copied")
    print("=" * 60)

if __name__ == "__main__":
    main()
