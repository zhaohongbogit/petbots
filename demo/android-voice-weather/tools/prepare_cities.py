#!/usr/bin/env python3
"""Build a small offline city table from pinned public datasets; no runtime geocoding."""
import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    'city_list': ('https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist/pc.json', 'd28f1ecaa92519ac7e759309f23b4a1080e05eb0816c66a9ac0cc55be8aa02e1'),
    'geonames': ('https://download.geonames.org/export/dump/CN.zip', 'a545f52444c6d2f2883e7a3ad112a0f26fc17947e10360e0ab8d8202d9d73716'),
}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--city-list', type=Path)
    parser.add_argument('--geonames', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as temporary:
        data = {}
        for key, (url, checksum) in SOURCES.items():
            path = getattr(args, key) or Path(temporary) / key
            if not getattr(args, key):
                urllib.request.urlretrieve(url, path)
            data[key] = path.read_bytes()
            if hashlib.sha256(data[key]).hexdigest() != checksum:
                raise SystemExit('Source changed; review before updating pinned checksum: ' + key)
        divisions = json.loads(data['city_list'])
        names = {name[:-1] for group in divisions.values() for name in group if name.endswith('市')}
        names.update(name[:-1] for name in divisions if name.endswith('市'))
        import io
        records = {}
        for line in zipfile.ZipFile(io.BytesIO(data['geonames'])).read('CN.txt').decode().splitlines():
            row = line.split('\t')
            if row[6] != 'P':
                continue
            aliases = set(row[3].split(',')) | {row[1]}
            aliases |= {alias[:-1] for alias in aliases.copy() if alias.endswith('市')}
            for name in names & aliases:
                score = (int(row[14]), row[7] in ('PPLC', 'PPLA', 'PPLA2'))
                if name not in records or score > records[name][0]:
                    records[name] = (score, row)
        output = ROOT / 'app/src/main/assets/cities.tsv'
        lines = ['# name\tlatitude\tlongitude\tgeonames_id', '# GeoNames CC BY 4.0; see docs/城市数据来源.md']
        for name in sorted(records):
            row = records[name][1]
            lines.append('\t'.join((name, row[4], row[5], row[0])))
        output.write_text('\n'.join(lines) + '\n')
        print('Cities:', len(records), 'Unmatched:', ', '.join(sorted(names - records.keys())))

if __name__ == '__main__':
    main()
