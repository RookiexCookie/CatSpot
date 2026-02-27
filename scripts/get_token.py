#!/usr/bin/env python3
"""Get a Spotify OAuth access token using PKCE flow and copy it to clipboard.

Opens your browser to Spotify login, captures the callback on a local server,
exchanges the auth code for an access token, and copies it to your clipboard
for pasting into the sidespot app.
"""

import hashlib
import base64
import secrets
import webbrowser
import subprocess
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlencode, urlparse, parse_qs

# librespot's keymaster client ID (no client secret needed with PKCE)
CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
REDIRECT_URI = "http://127.0.0.1:8898/login"
SCOPES = "streaming playlist-read playlist-read-private user-library-read user-library-modify playlist-modify-public playlist-modify-private user-read-playback-state user-modify-playback-state user-read-currently-playing user-read-private"

TOKEN_URL = "https://accounts.spotify.com/api/token"
AUTH_URL = "https://accounts.spotify.com/authorize"


def generate_pkce():
    code_verifier = secrets.token_urlsafe(64)[:128]
    digest = hashlib.sha256(code_verifier.encode("ascii")).digest()
    code_challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return code_verifier, code_challenge


def copy_to_clipboard(text):
    try:
        subprocess.run(["pbcopy"], input=text.encode(), check=True)
        return True
    except (subprocess.CalledProcessError, FileNotFoundError):
        return False


class CallbackHandler(BaseHTTPRequestHandler):
    auth_code = None

    def do_GET(self):
        parsed = urlparse(self.path)
        params = parse_qs(parsed.query)

        if "code" in params:
            CallbackHandler.auth_code = params["code"][0]
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(b"<html><body><h2>Success! You can close this tab.</h2></body></html>")
        else:
            error = params.get("error", ["unknown"])[0]
            self.send_response(400)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(f"<html><body><h2>Error: {error}</h2></body></html>".encode())

    def log_message(self, format, *args):
        pass  # Suppress request logging


def exchange_code(auth_code, code_verifier):
    import urllib.request
    import json

    data = urlencode({
        "grant_type": "authorization_code",
        "code": auth_code,
        "redirect_uri": REDIRECT_URI,
        "client_id": CLIENT_ID,
        "code_verifier": code_verifier,
    }).encode()

    req = urllib.request.Request(TOKEN_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")

    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())


def main():
    code_verifier, code_challenge = generate_pkce()

    auth_params = urlencode({
        "client_id": CLIENT_ID,
        "response_type": "code",
        "redirect_uri": REDIRECT_URI,
        "scope": SCOPES,
        "code_challenge_method": "S256",
        "code_challenge": code_challenge,
    })
    auth_url = f"{AUTH_URL}?{auth_params}"

    server = HTTPServer(("127.0.0.1", 8898), CallbackHandler)
    server.timeout = 120

    print("Opening Spotify login in your browser...")
    webbrowser.open(auth_url)
    print("Waiting for callback (timeout: 120s)...")

    while CallbackHandler.auth_code is None:
        server.handle_request()

    server.server_close()

    print("Got auth code, exchanging for token...")
    token_data = exchange_code(CallbackHandler.auth_code, code_verifier)

    access_token = token_data["access_token"]
    expires_in = token_data.get("expires_in", "?")

    if copy_to_clipboard(access_token):
        print(f"\nAccess token copied to clipboard! (expires in {expires_in}s)")
    else:
        print(f"\nAccess token (expires in {expires_in}s):")
        print(access_token)

    print("\nPaste it into the sidespot app's token field on the emulator.")


if __name__ == "__main__":
    main()
