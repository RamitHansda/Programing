#!/usr/bin/env python3
"""CLI demo for the ServiceLane car service AI agent."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from agent.orchestrator import ServiceAgent
from agent.session import SessionState
from domain.store import STORE
from tools.registry import ToolRegistry


def main() -> None:
    parser = argparse.ArgumentParser(description="ServiceLane car service AI agent (CLI)")
    parser.add_argument("--trace", action="store_true", help="Print tool traces after each turn")
    parser.add_argument(
        "--script",
        nargs="*",
        help="Optional non-interactive messages to send in sequence",
    )
    args = parser.parse_args()

    agent = ServiceAgent(STORE, ToolRegistry(STORE))
    state = SessionState()

    print("ServiceLane — Car Service AI Agent")
    print("Type 'quit' to exit. Demo customers: Ananya Sharma, Rahul Mehta, Priya Nair\n")

    bootstrap = agent.handle("hello", state)
    _print_reply(bootstrap, args.trace)

    messages = list(args.script) if args.script else None
    idx = 0
    while True:
        if messages is not None:
            if idx >= len(messages):
                break
            user = messages[idx]
            idx += 1
            print(f"You: {user}")
        else:
            try:
                user = input("You: ").strip()
            except (EOFError, KeyboardInterrupt):
                print("\nBye.")
                break
        if not user:
            continue
        if user.lower() in {"quit", "exit", "q"}:
            print("Bye.")
            break
        result = agent.handle(user, state)
        _print_reply(result, args.trace)


def _print_reply(result: dict, trace: bool) -> None:
    print(f"\nServiceLane: {result['message']}")
    if result.get("suggestions"):
        print("Suggestions:", " | ".join(result["suggestions"]))
    if trace and result.get("tool_trace"):
        print("Tools:", json.dumps(result["tool_trace"], indent=2, default=str))
    print()


if __name__ == "__main__":
    main()
