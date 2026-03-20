#!/usr/bin/env python3
"""
Generate Pyrogram String Session for stateless authentication.

Usage:
    python get_session.py

This script creates a string session that can be stored as a GitHub Secret
(TELEGRAM_SESSION_STRING) for production deployment without interactive auth.

The generated session string is a permanent credential for your Telegram account
and can be used across multiple machines and deployments.
"""

from pyrogram import Client


def generate_session():
    """Interactively generate and display a Pyrogram string session."""
    print("\n" + "=" * 80)
    print("Pyrogram String Session Generator")
    print("=" * 80)
    print("\nThis will create a session string for your Telegram account.")
    print("You'll need:")
    print("  • API_ID and API_HASH from https://my.telegram.org/apps")
    print("  • Your phone number (to receive SMS code)")
    print("  • Your 2FA password (if enabled)")
    print("\n" + "-" * 80 + "\n")

    # Get credentials
    api_id = input("Enter your TELEGRAM_API_ID: ").strip()
    api_hash = input("Enter your TELEGRAM_API_HASH: ").strip()

    # Validate
    if not api_id or not api_hash:
        print("❌ API_ID and API_HASH are required")
        return

    if not api_id.isdigit():
        print("❌ API_ID must be numeric")
        return

    if len(api_hash) != 32:
        print("⚠️  API_HASH should be 32 characters, got {len(api_hash)}")

    # Create client and start interactive session
    print("\n" + "-" * 80)
    print("Starting authentication...\n")

    try:
        with Client(
            name="temp_auth",
            api_id=int(api_id),
            api_hash=api_hash,
            workdir="./",  # Temporary session file
        ) as app:
            # This will prompt for phone number, SMS code, 2FA password
            session_string = app.export_session_string()

            print("\n" + "=" * 80)
            print("✅ Session generated successfully!")
            print("=" * 80)
            print("\n📋 Session String (store as GitHub Secret TELEGRAM_SESSION_STRING):\n")
            print(session_string)
            print("\n" + "=" * 80)
            print("\n📝 Next steps:")
            print("  1. Copy the session string above")
            print("  2. Add to GitHub Secrets: Settings → Secrets → TELEGRAM_SESSION_STRING")
            print("  3. Commit the code, push to main, and let CI/CD deploy automatically")
            print("\n⚠️  Keep this session string SECRET — it grants full access to your account!")
            print("=" * 80 + "\n")

    except Exception as e:
        print(f"\n❌ Error during authentication: {e}")
        print("Make sure your API_ID and API_HASH are correct")


if __name__ == "__main__":
    generate_session()
