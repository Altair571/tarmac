#!/usr/bin/env python3
"""Install an Android Auto head-unit identity into the git-ignored local-auth/android-auto/ folder.

Usage: scripts/android_auto_identity.py CERTIFICATE PRIVATE_KEY [--dest FOLDER]

The certificate may be PEM or DER; the key must be PEM (PKCS#8 or PKCS#1 RSA). The script checks
that the key belongs to the certificate, prints only the certificate's subject, issuer and dates,
never the key, and refuses a destination that git would track. See docs/ANDROID_AUTO.md: the
identity is for personal builds only and must never be committed, pushed or shared.
"""
import argparse
import hashlib
import os
import subprocess
import sys
from pathlib import Path
from typing import Optional

root = Path(__file__).resolve().parents[1]


def openssl(*arguments: str, data: Optional[bytes] = None) -> bytes:
    try:
        return subprocess.run(['openssl', *arguments], input=data, check=True, capture_output=True).stdout
    except FileNotFoundError:
        raise SystemExit('openssl is required')
    except subprocess.CalledProcessError as error:
        raise SystemExit(f'openssl {arguments[0]} failed: {error.stderr.decode(errors="replace").strip()}')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('certificate', type=Path)
    parser.add_argument('private_key', type=Path)
    parser.add_argument('--dest', type=Path, default=root / 'local-auth' / 'android-auto')
    options = parser.parse_args()

    certificate = options.certificate.read_bytes()
    if b'-----BEGIN CERTIFICATE-----' not in certificate:
        certificate = openssl('x509', '-inform', 'DER', '-outform', 'PEM', data=certificate)
    key = options.private_key.read_bytes()
    if b'PRIVATE KEY-----' not in key:
        raise SystemExit('the private key must be PEM (BEGIN PRIVATE KEY or BEGIN RSA PRIVATE KEY)')

    # The moduli are public, but compare digests so nothing derived from the key is printed.
    certificate_modulus = hashlib.sha256(openssl('x509', '-noout', '-modulus', data=certificate)).hexdigest()
    key_modulus = hashlib.sha256(openssl('rsa', '-noout', '-modulus', data=key)).hexdigest()
    if certificate_modulus != key_modulus:
        raise SystemExit('the private key does not belong to the certificate')

    details = openssl('x509', '-noout', '-subject', '-issuer', '-dates', data=certificate).decode()
    print(details.strip())
    if 'Google Automotive Link' not in details:
        print('warning: the issuer is not Google Automotive Link; Android Auto will reject this certificate',
              file=sys.stderr)

    destination = options.dest.resolve()
    destination.mkdir(parents=True, exist_ok=True)
    os.chmod(destination, 0o700)
    for name in ('certificate.pem', 'private-key.pem'):
        tracked = subprocess.run(['git', 'check-ignore', '-q', str(destination / name)], cwd=root).returncode != 0
        if tracked:
            raise SystemExit(f'{destination / name} is not ignored by git; refusing to write identity material there')
    for name, data in (('certificate.pem', certificate), ('private-key.pem', key)):
        path = destination / name
        path.write_bytes(data)
        os.chmod(path, 0o600)
    print(f'Installed the Android Auto head-unit identity in {destination}')
    print('A personal build picks it up from local-auth/; see docs/ANDROID_AUTO.md. Never commit or share it.')


if __name__ == '__main__':
    main()
