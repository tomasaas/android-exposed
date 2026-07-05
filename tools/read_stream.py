#!/usr/bin/env python3
import argparse
import json
import sys
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description="Read Android Exposed JSONL sensor stream.")
    parser.add_argument("--url", default="http://127.0.0.1:8765/stream")
    parser.add_argument("--output", help="Optional .jsonl file to write samples to.")
    parser.add_argument("--limit", type=int, default=0, help="Stop after this many samples. 0 means forever.")
    args = parser.parse_args()

    out = open(args.output, "a", encoding="utf-8") if args.output else None
    count = 0

    try:
        with urllib.request.urlopen(args.url, timeout=15) as response:
            for raw_line in response:
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line:
                    continue

                try:
                    sample = json.loads(line)
                except json.JSONDecodeError:
                    print(f"Bad JSON: {line}", file=sys.stderr)
                    continue

                print(sample)
                if out:
                    out.write(json.dumps(sample, separators=(",", ":")) + "\n")
                    out.flush()

                count += 1
                if args.limit and count >= args.limit:
                    break
    except urllib.error.URLError as exc:
        print(f"Could not connect to {args.url}: {exc}", file=sys.stderr)
        print("Check that the app is open and run: adb forward tcp:8765 tcp:8765", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 0
    finally:
        if out:
            out.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
