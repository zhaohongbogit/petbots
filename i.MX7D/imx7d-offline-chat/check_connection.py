#!/usr/bin/env python3
"""Read-only checks. Does not submit text, plans or device actions."""
import json
import urllib.request
import urllib.error
from provision import adb, BOARD, BP, board_ip

host = board_ip()
key = json.loads(adb(BOARD, 'shell', '-T', 'run-as', BP, 'cat', 'files/lan.json').stdout)['key']
url = 'http://' + host + ':8777'
for path in ['/health', '/status?id=readonly-check-00000000']:
    request = urllib.request.Request(url+path, headers={'X-Pet-Key': key})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            result = json.load(response)
            print(path.split('?')[0], response.status, json.dumps(result, ensure_ascii=False))
    except urllib.error.HTTPError as error:
        expected = path.startswith('/status') and error.code == 404
        print(path.split('?')[0], error.code, 'expected missing job' if expected else 'unexpected failure')
        if not expected:
            raise SystemExit(1)
try:
    urllib.request.urlopen(url+'/health', timeout=10)
    raise SystemExit('Unauthenticated health incorrectly accepted')
except urllib.error.HTTPError as error:
    if error.code != 403:
        raise SystemExit('Unexpected unauthenticated response')
    print('unauthenticated request: 403, rejected')
