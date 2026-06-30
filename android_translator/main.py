import os
import sys
import argparse
import webbrowser
import threading
import time

from .web.server import start_web_server

def auto_detect_res_dir() -> str:
    """
    Tries to automatically detect the Android resource directory in the current working directory.
    Checks for typical paths like:
    1. app/src/main/res/
    2. src/main/res/
    3. res/
    If none of these are found, defaults to the current working directory.
    """
    cwd = os.getcwd()
    
    candidates = [
        os.path.join(cwd, "app", "src", "main", "res"),
        os.path.join(cwd, "src", "main", "res"),
        os.path.join(cwd, "res"),
    ]
    
    for candidate in candidates:
        if os.path.exists(candidate) and os.path.isdir(candidate):
            return candidate
            
    return cwd

def open_browser_after_delay(url: str, delay: float = 0.5):
    """Opens browser in a daemon thread after a brief server-warmup delay."""
    def target():
        time.sleep(delay)
        webbrowser.open(url)
    threading.Thread(target=target, daemon=True).start()

def main():
    parser = argparse.ArgumentParser(
        description="Android XML Translation Manager (Local Web Workspace)"
    )
    
    # Resource directory scanning mode
    parser.add_argument(
        "--res-dir",
        type=str,
        help="Scan the entire Android resource directory (looks for app/src/main/res by default)"
    )
    
    # Direct file editing mode
    parser.add_argument(
        "--source",
        type=str,
        help="Path to the source XML file (typically values/strings.xml)"
    )
    parser.add_argument(
        "--target",
        type=str,
        help="Path to the target translation XML file (e.g. values-es/strings.xml)"
    )
    
    # Port specification
    parser.add_argument(
        "--port",
        type=int,
        default=5000,
        help="Port to run the local web server on (default: 5000)"
    )

    args = parser.parse_args()
    url = f"http://127.0.0.1:{args.port}"

    # 1. Check for single-file mode
    if args.source or args.target:
        if not args.source or not args.target:
            print("Error: Both --source and --target must be specified for single-file mode.", file=sys.stderr)
            sys.exit(1)
            
        if not os.path.exists(args.source):
            print(f"Error: Source file does not exist: {args.source}", file=sys.stderr)
            sys.exit(1)
            
        print(f"Running Android XML Translation Manager...")
        print(f"Web server running at {url}")
        print("Press Ctrl+C to stop.")
        
        # Start server in single file mode
        start_web_server(
            source_xml=os.path.abspath(args.source),
            target_xml=os.path.abspath(args.target),
            port=args.port
        )
        
    # 2. Otherwise, run in resource directory mode
    else:
        res_dir = args.res_dir
        if not res_dir:
            res_dir = auto_detect_res_dir()
            print(f"No arguments provided. Auto-detected resource directory: {res_dir}")

        res_dir = os.path.abspath(res_dir)
        source_xml = os.path.join(res_dir, "values", "strings.xml")
        
        if not os.path.exists(source_xml):
            print(
                f"Error: Could not find base strings file at '{source_xml}'.\n"
                f"Please ensure you are running the command in an Android project root or "
                f"specify a valid resource directory using --res-dir.",
                file=sys.stderr
            )
            sys.exit(1)

        print(f"Running Android XML Translation Manager...")
        print(f"Web server running at {url}")
        print("Press Ctrl+C to stop.")
        
        # Start server in directory scanning mode
        start_web_server(res_dir=res_dir, port=args.port)

if __name__ == "__main__":
    main()
