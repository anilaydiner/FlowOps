#!/usr/bin/env python3
"""Read-only machine acceptance probe. Never certifies manual deployment/provider acceptance."""
import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--phase', choices=['PRE_ENABLE', 'POST_ENABLE'], default='PRE_ENABLE')
    args = parser.parse_args()
    token = os.environ.get('FLOWOPS_OPERATOR_TOKEN')
    if not token:
        parser.error('FLOWOPS_OPERATOR_TOKEN is required; use an active global execution rollout operator')
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme not in ('http', 'https') or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
        parser.error('--base-url must be an HTTP(S) application URL without credentials, query or fragment')
    url = args.base_url.rstrip('/') + '/api/v1/execution/rollout?phase=' + args.phase
    request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token, 'Accept': 'application/json'}, method='GET')
    # Never follow a redirect with the operator credential.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            report = json.load(response)
        if report.get('phase') != args.phase or not isinstance(report.get('checks'), list) or not isinstance(report.get('manualAcceptanceRequired'), list):
            raise ValueError('Invalid rollout response')
        required = {'SCHEMA_NOT_CURRENT', 'WORKER_STATE', 'PROVIDER_CAPACITY', 'NO_GLOBAL_EXECUTION_OPERATOR'}
        if not required.issubset({c.get('code') for c in report['checks']}) or not report['manualAcceptanceRequired']:
            raise ValueError('Incomplete rollout response')
        for check in report['checks']:
            print(f"{check['status']:9} {check['code']}: {check['count']} — {check['summary']}")
        print('\nManual acceptance remains required:')
        for requirement in report['manualAcceptanceRequired']:
            print('- ' + requirement)
        passed = report.get('machineChecksPassed') is True and all(c.get('status') in ('PASS', 'ATTENTION') for c in report['checks'])
        print('\nMACHINE_CHECKS_PASS' if passed else '\nMACHINE_CHECKS_BLOCKED')
        return 0 if passed else 2
    except (urllib.error.URLError, ValueError, KeyError, TypeError):
        print('Could not obtain a valid authorized rollout report; no acceptance was granted.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
