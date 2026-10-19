#!/usr/bin/env python3
"""v3.98.38: syntax check of every inline <script> in the Kwallet and Hwallet pages with `node --check`.
Fails the build before Gradle runs when a page would not even parse (the class of bug that blanks a whole app)."""
import re, sys, subprocess, tempfile, os
bad = 0
for f in sys.argv[1:] or ['web/index.html', 'hwallet/web/index.html']:
    s = open(f, encoding='utf-8').read(); n = 0
    for i, m in enumerate(re.finditer(r'<script([^>]*)>(.*?)</script>', s, re.S)):
        attrs, body = m.group(1), m.group(2)
        if 'application/json' in attrs or 'src=' in attrs or not body.strip(): continue
        n += 1
        with tempfile.NamedTemporaryFile('w', suffix='.js', delete=False, encoding='utf-8') as t: t.write(body); p = t.name
        r = subprocess.run(['node', '--check', p], capture_output=True, text=True); os.unlink(p)
        if r.returncode:
            bad += 1; line = s[:m.start()].count('\n') + 1
            print('::error file=%s,line=%d::script block does not parse\n%s' % (f, line, r.stderr[:1500]))
    print('%s: %d script blocks checked' % (f, n))
sys.exit(1 if bad else 0)
