"""Generate an ignored, local-test-only APK login asset; never prints credentials."""
import getpass
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urlsplit

base = sys.argv[1] if len(sys.argv) == 2 else ''
u = urlsplit(base)
if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.password or u.query or u.fragment:
    raise SystemExit('Usage: python3 configure_test_login.py <test-agent-base>')
phone = getpass.getpass('Test mobile (hidden): ').strip()
if phone.startswith('+86'):
    phone = phone[3:].replace(' ', '')
captcha = getpass.getpass('Test captcha (hidden): ').strip()
if not re.fullmatch(r'1[0-9]{10}', phone) or not re.fullmatch(r'[0-9]{4,8}', captcha):
    raise SystemExit('Invalid test-login format')
file = Path(__file__).resolve().parent / 'build/hbc-private-login.json'
file.parent.mkdir(parents=True, exist_ok=True)
fd = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, 'w') as stream:
    json.dump(dict(environment='hbc-test', countryCode='86', phone=phone, captcha=captcha, agentBase=base.rstrip('/')), stream)
print('Private test-login asset prepared; values are not displayed.')
