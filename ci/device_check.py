#!/usr/bin/env python3
"""Kwallet + Hwallet emulator check (Push 3 P0).

Drives the real apps on an Android emulator: real taps / typing / swipes go through `adb shell input`, and the
WebViews are inspected (and, only where a tap cannot be aimed, poked) over Chrome DevTools, which the debug builds
and the -PkwE2E=true release builds enable (BuildConfig.E2E). Nothing here needs secrets or a seed.

Every step writes a screenshot + a logcat slice into OUT and a line into OUT/results.json. A failing step is a
finding, not a crash: the script only exits non-zero when the harness itself cannot run (no device, no APK).

usage: device_check.py <out_dir> <label>      (env ADB=adb)
"""
import json, os, re, subprocess, sys, time, urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else 'device-out'
LABEL = sys.argv[2] if len(sys.argv) > 2 else 'debug'
ADB = os.environ.get('ADB', 'adb')
H, K = 'app.hwallet', 'app.kwallet'
WATCH_ETH = '0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045'  # a well-known public address (watch only)
os.makedirs(OUT, exist_ok=True)
R = {'label': LABEL, 'steps': []}
T0 = time.time()
SEQ = [0]


def log(*a):
    print('[%6.1fs]' % (time.time() - T0), *a, flush=True)


def sh(cmd, timeout=60, check=False):
    p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode:
        raise RuntimeError('%s -> %s %s' % (cmd, p.returncode, p.stderr[-400:]))
    return p.stdout


def adb(args, timeout=60):
    return sh('%s %s' % (ADB, args), timeout)


def shot(name):
    SEQ[0] += 1
    fn = '%02d_%s.png' % (SEQ[0], name)
    try:
        data = subprocess.run([ADB, 'exec-out', 'screencap', '-p'], capture_output=True, timeout=30).stdout
    except subprocess.TimeoutExpired:
        data = b''
    with open(os.path.join(OUT, fn), 'wb') as f:
        f.write(data)
    if not data:
        log('screencap empty for', fn, 'device alive:', alive())
    return fn


def alive():
    try:
        return subprocess.run([ADB, 'shell', 'echo', 'ok'], capture_output=True, text=True, timeout=15).stdout.strip() == 'ok'
    except Exception:
        return False


MARK = ['']


def mark():
    """Guest clock now; the next logcat slice starts here (no `logcat -c`: clearing ended the -b all stream on API 34)."""
    MARK[0] = adb('shell date +%m-%d_%H:%M:%S.000', 15).strip().replace('_', ' ')


def logcat(name):
    fn = 'logcat_%02d_%s.txt' % (SEQ[0], name)
    try:
        since = ('-T "\'%s\'"' % MARK[0]) if MARK[0] else '-t 4000'
        open(os.path.join(OUT, fn), 'w').write(adb('logcat -d -v threadtime %s' % since, 45))
        mark()
    except Exception as e:
        open(os.path.join(OUT, fn), 'w').write('logcat failed: %r (device alive: %s)' % (e, alive()))
    return fn


def step(name, ok, **info):
    if not alive():
        info['device_lost'] = True
    info.update(step=name, ok=bool(ok), t=round(time.time() - T0, 1))
    R['steps'].append(info)
    json.dump(R, open(os.path.join(OUT, 'results.json'), 'w'), indent=1)
    if info.get('device_lost'):
        R['harness_error'] = 'emulator stopped responding during ' + name
        json.dump(R, open(os.path.join(OUT, 'results.json'), 'w'), indent=1)
        raise SystemExit(3)
    log('STEP', name, 'OK' if ok else 'FAIL', json.dumps({k: v for k, v in info.items() if k not in ('step', 'ok')})[:600])
    json.dump(R, open(os.path.join(OUT, 'results.json'), 'w'), indent=1)


# ---------------- DevTools ----------------
class Page:
    def __init__(self, ws_url):
        import websocket  # pip install websocket-client
        self.ws = websocket.create_connection(ws_url, timeout=20, suppress_origin=True)
        self.n = 0

    def ev(self, expr, timeout=20):
        self.n += 1
        self.ws.send(json.dumps({'id': self.n, 'method': 'Runtime.evaluate',
                                 'params': {'expression': expr, 'returnByValue': True, 'awaitPromise': True}}))
        end = time.time() + timeout
        while time.time() < end:
            m = json.loads(self.ws.recv())
            if m.get('id') == self.n:
                r = m.get('result', {})
                if 'exceptionDetails' in r:
                    return {'__err': r['exceptionDetails'].get('text', '') + ' ' + str(r['exceptionDetails'].get('exception', {}).get('description', ''))[:300]}
                return r.get('result', {}).get('value')
        return {'__err': 'timeout'}

    def close(self):
        try: self.ws.close()
        except Exception: pass


PORT = {H: 9222, K: 9223}


def devtools_pages(pkg, wait=20):
    """All DevTools targets of pkg's process (the Hwallet page + one per open Browser tab)."""
    end = time.time() + wait
    while time.time() < end:
        pid = adb('shell pidof %s' % pkg).strip().split(' ')[0]
        if pid:
            if 'webview_devtools_remote_%s' % pid in adb('shell cat /proc/net/unix'):
                adb('forward tcp:%d localabstract:webview_devtools_remote_%s' % (PORT[pkg], pid))
                try:
                    return json.load(urllib.request.urlopen('http://127.0.0.1:%d/json' % PORT[pkg], timeout=10))
                except Exception as e:
                    log('devtools list failed', e)
        time.sleep(1)
    return []


def main_page(pkg, wait=25):
    end = time.time() + wait
    while time.time() < end:
        for p in devtools_pages(pkg, 5):
            u = p.get('url', '')
            if p.get('type') == 'page' and 'appassets.androidplatform.net' in u:
                return Page(p['webSocketDebuggerUrl']), p
        time.sleep(1)
    return None, None


SCREEN = {}


def screen_geo(pg):
    m = re.search(r'(\d+)x(\d+)', adb('shell wm size').split('Override')[-1])
    SCREEN['w'], SCREEN['h'] = int(m.group(1)), int(m.group(2))
    g = pg.ev('({dpr: devicePixelRatio, iw: innerWidth, ih: innerHeight})') or {}
    SCREEN.update(g)
    # the Hwallet page is edge to edge: css px * dpr = screen px from the top-left corner
    SCREEN['ox'] = 0
    SCREEN['oy'] = max(0, SCREEN['h'] - round(g.get('ih', 0) * g.get('dpr', 1))) if g.get('ih', 0) * g.get('dpr', 1) < SCREEN['h'] - 4 else 0
    return SCREEN


def rect(pg, sel):
    return pg.ev("(function(){var e=document.querySelector(%s); if(!e) return null; var r=e.getBoundingClientRect(); "
                 "return {x:r.left+r.width/2, y:r.top+r.height/2, w:r.width, h:r.height}})()" % json.dumps(sel))


def tap(pg, sel):
    r = rect(pg, sel)
    if not r or not r['w']:
        return None
    x = round(SCREEN['ox'] + r['x'] * SCREEN['dpr']); y = round(SCREEN['oy'] + r['y'] * SCREEN['dpr'])
    adb('shell input tap %d %d' % (x, y))
    return [x, y]


def app_text(pg, n=260):
    t = pg.ev("(document.getElementById('app')||document.body).innerText") or ''
    return re.sub(r'\s+', ' ', t if isinstance(t, str) else str(t))[:n]


def toast(pg):
    return pg.ev("(function(){var t=document.getElementById('toast'); return t ? t.textContent : ''})()")


def launch_w(pkg):
    comp = adb("shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER %s" % pkg).strip().splitlines()[-1]
    skip = os.environ.get('SKIP', '').strip()
    out = adb('shell am start -W -n %s%s' % (comp, (' -e kw_e2e_skip ' + skip) if skip else ''), 60)
    tt = re.search(r'TotalTime: (\d+)', out)
    return comp, (int(tt.group(1)) if tt else None), out.strip()[-300:]


# ---------------- flows ----------------
def run():
    if not alive():
        print('no device'); sys.exit(2)
    for p in (H, K):
        if p not in adb('shell pm list packages %s' % p):
            print('missing package', p); sys.exit(2)
    adb('shell pm grant %s android.permission.POST_NOTIFICATIONS' % H)
    adb('shell settings put system screen_off_timeout 1800000')
    adb('shell svc power stayon true')
    adb('shell input keyevent 82')
    mark()
    R['sanity_shot'] = shot('sanity_launcher')  # proves screencap works before any of our apps runs
    R['sanity_bytes'] = os.path.getsize(os.path.join(OUT, R['sanity_shot']))
    R['device'] = {'sdk': adb('shell getprop ro.build.version.sdk').strip(), 'model': adb('shell getprop ro.product.model').strip(),
                   'webview': re.sub(r'\s+', ' ', adb('shell dumpsys webviewupdate | grep -i -m2 "Current WebView package\\|versionName"'))[:200],
                   'h_version': re.findall(r'versionName=(\S+)', adb('shell dumpsys package %s' % H))[:1],
                   'k_version': re.findall(r'versionName=(\S+)', adb('shell dumpsys package %s' % K))[:1]}
    log('device', R['device'])

    # 1. launch Hwallet
    R['vibrate'] = os.environ.get('VIB', 'block'); R['e2e_skip'] = os.environ.get('SKIP', '')
    R['ringer'] = re.sub(r'\s+', ' ', adb('shell dumpsys audio | grep -m3 -i "ringer mode"'))[:200]
    R['appops_vibrate'] = {p: adb('shell appops get %s VIBRATE' % p).strip() for p in (H, K)}
    comp, tt, raw = launch_w(H)
    for i in range(12):  # watch the first 6 s closely: the emulator used to die ~2 s after launch
        time.sleep(0.5)
        if not alive():
            R['died_after_launch_s'] = round((i + 1) * 0.5, 1)
            step('h_launch', False, why='emulator died %.1fs after am start -W (see logcat_stream_all.txt, host/dmesg.txt)' % ((i + 1) * 0.5), comp=comp, am_total_ms=tt)
    shot('h_launch_6s')
    R['h_proc'] = adb('shell pidof %s' % H).strip()
    log('h launched', comp, tt, 'pid', R['h_proc'], 'alive', alive())
    pg, meta = main_page(H, 30)
    if not pg:
        time.sleep(8); f = shot('h_launch'); step('h_launch', False, why='no DevTools page for Hwallet (WebView debugging off or app crashed)', comp=comp, total_ms=tt, shot=f, logcat=logcat('h_launch'))
        return
    t_ready = None
    for i in range(40):
        st = pg.ev("({app: !!document.getElementById('app') && document.getElementById('app').children.length, opening: !!document.getElementById('kl-root')})") or {}
        if st.get('app') and not st.get('opening'):
            t_ready = round(time.time() - T0, 1); break
        time.sleep(0.5)
    screen_geo(pg)
    R['screen'] = dict(SCREEN)
    f = shot('h_launch')
    step('h_launch', bool(t_ready), comp=comp, am_total_ms=tt, page_url=meta.get('url'), ui_ready_at=t_ready, text=app_text(pg), shot=f, logcat=logcat('h_launch'))

    # 2. watch-only setup (public address) so Home, Swap and the Browser are reachable
    if pg.ev("!!document.querySelector('[data-msave]') && !document.querySelector('.hbar')"):
        pg.ev("(function(){var d=document.querySelector('details.man'); if(d) d.open=true; var i=document.querySelector('.mi[data-k=eth]'); i.value=%s; i.dispatchEvent(new Event('input',{bubbles:true})); document.querySelector('[data-msave]').click(); return 1})()" % json.dumps(WATCH_ETH))
        for i in range(20):
            if pg.ev("!!document.querySelector('.hbar')"): break
            time.sleep(0.5)
    time.sleep(5)  # let balances/prices load
    home = pg.ev("!!document.querySelector('.hbar')")
    f = shot('h_home')
    # a system dialog over Hwallet (e.g. "Let app always run in background?", asked on first Home) eats the next taps:
    # record it as a finding, then dismiss it with Back so the flows continue like a user who said no
    sysdlg = None
    for i in range(3):
        foc = re.findall(r'mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}', adb('shell dumpsys window windows | grep -m1 mCurrentFocus'))
        if foc and not foc[0].startswith(H):
            sysdlg = sysdlg or foc[0]; adb('shell input keyevent 4'); time.sleep(1.5)
        else:
            break
    step('h_home_watch_setup', home, text=app_text(pg), toast=toast(pg), system_dialog=sysdlg, shot=f, logcat=logcat('h_home'))

    # 3. Browser tab -> type google.com -> page loads in the native WebView
    xy = tap(pg, '[data-tab="browser"]'); time.sleep(2)
    on_br = pg.ev("!!document.getElementById('br-host')")
    how = 'tap'
    if not on_br:
        how = 'js-click (tap missed)'; pg.ev("document.querySelector('[data-tab=\"browser\"]').click()"); time.sleep(2)
        on_br = pg.ev("!!document.getElementById('br-host')")
    f = shot('h_browser_tab')
    step('h_browser_tab', on_br, how=how, tap=xy, text=app_text(pg), shot=f, logcat=logcat('h_browser_tab'))

    typed = 'none'
    if on_br:
        if not rect(pg, '#br-in'):  # start page may need a new tab first
            pg.ev("(function(){var b=document.querySelector('[data-br-new],[data-new],.br-new'); if(b) b.click(); return !!b})()"); time.sleep(1)
        xy = tap(pg, '#br-in'); time.sleep(1.2)
        focused = pg.ev("document.activeElement && document.activeElement.id")
        if focused == 'br-in':
            adb("shell input text 'google.com'"); time.sleep(0.6)
            val = pg.ev("document.getElementById('br-in') && document.getElementById('br-in').value")
            adb('shell input keyevent 66'); typed = 'adb-input value=%r' % val
        else:
            pg.ev("(function(){var i=document.getElementById('br-in'); i.focus(); i.value='google.com'; var f=document.getElementById('br-f'); if(f.requestSubmit) f.requestSubmit(); else f.dispatchEvent(new Event('submit',{cancelable:true,bubbles:true})); return 1})()")
            typed = 'js-submit (focus was %r after tapping %r)' % (focused, xy)
        time.sleep(4)
        shot('h_browser_loading')
        found, info = None, {}
        for i in range(25):
            for p in devtools_pages(H, 3):
                if p.get('type') == 'page' and 'google.' in p.get('url', ''):
                    found = p; break
            if found:
                gp = Page(found['webSocketDebuggerUrl'])
                info = gp.ev("({state: document.readyState, title: document.title, len: document.body ? document.body.innerText.length : 0, inputs: document.querySelectorAll('input,textarea').length, w: innerWidth, h: innerHeight, vis: document.visibilityState})") or {}
                gp.close()
                if info.get('state') == 'complete' and info.get('len', 0) > 20: break
                found = None if i < 24 else found
            time.sleep(1)
        bar = pg.ev("(function(){var v=document.getElementById('br-v'), p=document.getElementById('br-pr'); var r=v&&v.getBoundingClientRect(); return {brv: r ? [Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)] : null, progress: p ? p.className + ' ' + (p.firstChild && p.firstChild.style.width) : null, ph: !!document.querySelector('.br-ph'), input: document.getElementById('br-in') && document.getElementById('br-in').value}})()")
        targets = [{'url': p.get('url', '')[:120], 'title': p.get('title', '')[:60], 'attached': p.get('attached'), 'desc': p.get('description', '')[:160]} for p in devtools_pages(H, 3)]
        f = shot('h_browser_google')
        step('h_browser_google', bool(found) and info.get('len', 0) > 20, typed=typed, google=info, bar=bar, targets=targets, toast=toast(pg), shot=f, logcat=logcat('h_browser_google'))

    # 4. Swap screen (from Home)
    tap(pg, '[data-tab="home"]'); time.sleep(2)
    if not pg.ev("!!document.querySelector('.hbar')"):
        pg.ev("document.querySelector('[data-tab=\"home\"]').click()"); time.sleep(2)
    xy = tap(pg, '[data-act="swap"]'); time.sleep(5)
    sw = pg.ev("!!document.getElementById('swp')")
    f = shot('h_swap')
    step('h_swap', sw, tap=xy, text=app_text(pg, 400), toast=toast(pg), shot=f, logcat=logcat('h_swap'))
    f2 = None
    if sw:  # token picker (where #84 says the chain chips are clipped)
        pk = pg.ev("(function(){var b=document.querySelector('#swp .sw-tok'); if(!b) return null; var r=b.getBoundingClientRect(); return {x:r.left+r.width/2,y:r.top+r.height/2,w:r.width}})()")
        if pk and pk.get('w'):
            adb('shell input tap %d %d' % (round(pk['x'] * SCREEN['dpr']), round(SCREEN['oy'] + pk['y'] * SCREEN['dpr']))); time.sleep(2.5)
            f2 = shot('h_swap_picker'); step('h_swap_picker', True, text=app_text(pg, 300), shot=f2, logcat=logcat('h_swap_picker'))
            adb('shell input keyevent 4'); time.sleep(1.5)

    # 5. pull to refresh on Home: a real finger swipe, sampled every 250 ms for 12 s
    for i in range(3):
        if pg.ev("!!document.querySelector('.hbar')"): break
        adb('shell input keyevent 4'); time.sleep(1.5)
    if not pg.ev("!!document.querySelector('.hbar')"):
        pg.ev("document.querySelector('[data-tab=\"home\"]').click()"); time.sleep(2)
    w, h = SCREEN['w'], SCREEN['h']
    pg.ev("window.__ptrS=[];window.__ptrT0=performance.now();window.__ptrI=setInterval(function(){var r=document.querySelector('.ptr');window.__ptrS.push([Math.round(performance.now()-__ptrT0), r?r.style.height:'-', r?r.style.opacity:'-', document.querySelector('.ptr-on')?1:0, r&&r.firstChild?r.firstChild.style.transform:''])},250);1")
    subprocess.Popen([ADB, 'shell', 'input', 'swipe', str(w // 2), str(int(h * 0.30)), str(w // 2), str(int(h * 0.62)), '700'])
    time.sleep(0.5); fa = shot('h_ptr_pulling')
    time.sleep(1.0); fb = shot('h_ptr_released')
    time.sleep(4.0); fc = shot('h_ptr_after5s')
    time.sleep(6.5); fd = shot('h_ptr_after12s')
    samples = pg.ev("clearInterval(window.__ptrI); window.__ptrS") or []
    end = samples[-1] if samples and isinstance(samples, list) else None
    shown = any(s[1] not in ('-', '0px', '') for s in samples) if isinstance(samples, list) else False
    done = bool(end) and end[1] in ('-', '0px', '') and not end[3]
    step('h_pull_to_refresh', shown and done, ring_shown=shown, finished_and_hidden=done, samples=samples[::4] if isinstance(samples, list) else samples,
         shots=[fa, fb, fc, fd], logcat=logcat('h_ptr'))
    pg.close()

    # 6. launch Kwallet
    comp, tt, raw = launch_w(K)
    time.sleep(6)
    kp, km = main_page(K, 15)
    kt = None
    if kp:
        kt = kp.ev("document.body.innerText.slice(0,200)"); kv = kp.ev("typeof APP_VERSION !== 'undefined' ? APP_VERSION : null"); kp.close()
    else:
        kv = None
    f = shot('k_launch')
    step('k_launch', bool(kp), comp=comp, am_total_ms=tt, version=kv, text=re.sub(r'\s+', ' ', kt or '')[:200], shot=f, logcat=logcat('k_launch'))

    crash = adb('logcat -d -b crash')
    open(os.path.join(OUT, 'logcat_crash.txt'), 'w').write(crash)
    R['crashes'] = len(re.findall(r'FATAL EXCEPTION', crash))
    R['total_s'] = round(time.time() - T0, 1)
    json.dump(R, open(os.path.join(OUT, 'results.json'), 'w'), indent=1)


if __name__ == '__main__':
    try:
        run()
    except SystemExit:
        raise
    except Exception as e:
        import traceback; traceback.print_exc()
        R['harness_error'] = repr(e)[:500]
        try: shot('harness_error'); logcat('harness_error')
        except Exception: pass
        json.dump(R, open(os.path.join(OUT, 'results.json'), 'w'), indent=1)
    finally:
        ok = sum(1 for s in R['steps'] if s['ok'])
        print('SUMMARY %s: %d/%d steps ok%s' % (LABEL, ok, len(R['steps']), ' (harness error)' if R.get('harness_error') else ''))
