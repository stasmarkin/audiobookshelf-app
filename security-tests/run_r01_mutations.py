"""Run only in an isolated prepared checkout, with no concurrent editor/build.

Uses synthetic loopback fixtures. No installs, devices or production credentials.
Mutations restore source bytes in finally; hard termination requires reviewing/restoring git diff.
Evidence must be outside the checkout. Run the unmodified full suite afterwards.
"""
import argparse, json, pathlib, subprocess, xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
NATIVE = ROOT / 'android/app/src/main/java/app/absplus/android/server/TokenRefresher.kt'
WEB = ROOT / 'plugins/capacitor/AbsDatabase.js'

def run(name, output, web=False):
    cmd = ['node', '--test', 'tests/refreshRedirect.test.mjs'] if web else [
        str(ROOT/'android/gradlew.bat'), ':app:testDebugUnitTest', '--offline', '--console=plain',
        '--tests', 'app.absplus.android.server.TokenRefresher*']
    result = subprocess.run(cmd, cwd=ROOT if web else ROOT/'android', capture_output=True,
                            text=True, encoding='utf8', errors='replace', timeout=240)
    log = result.stdout + result.stderr
    (output/(name+'.log')).write_text(log, encoding='utf8')
    failures = []
    if web:
        if 'ERR_ASSERTION' in log: failures.append({'test':'web redirect regression', 'type':'AssertionError'})
    else:
        for file in (ROOT/'android/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
            for case in ET.parse(file).findall('testcase'):
                failure = case.find('failure')
                if failure is not None:
                    failures.append({'test':case.get('classname')+'.'+case.get('name'),
                                     'type':failure.get('type'), 'message':failure.get('message')})
    return result.returncode, failures

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    output=pathlib.Path(parser.parse_args().output).resolve()
    assert not output.is_relative_to(ROOT)
    output.mkdir(parents=True, exist_ok=True)
    results=[]
    for web in (False, True):
        code, failures=run('baseline-web' if web else 'baseline-native',output,web)
        assert code==0 and not failures, 'Unmodified baseline must pass'
    mutations=[
        ('original_native_client',NATIVE,'refreshClient.newCall(request)','client.newCall(request)',False),
        ('enable_http_redirects',NATIVE,'.followRedirects(false)','.followRedirects(true)',False),
        ('redirect_discards_credentials',NATIVE,'if (response.code in 300..399) {',
         'if (response.code in 300..399) {\n      store.removeRefreshTokenIf(serverConnectionId, sentRefreshToken)',False),
        ('web_follows_redirects',WEB,"redirect: 'error'","redirect: 'follow'",True),
    ]
    for name,file,before,after,web in mutations:
        original=file.read_bytes()
        try:
            text=original.decode('utf8')
            assert text.count(before)==1, name
            file.write_bytes(text.replace(before,after).encode('utf8'))
            code,failures=run(name,output,web)
            assert code!=0 and any('Assertion' in f['type'] or 'ComparisonFailure' in f['type'] for f in failures), name+' survived or failed outside assertions'
            results.append({'name':name,'result':'KILLED','failures':failures})
            (output/'results.json').write_text(json.dumps(results,indent=2),encoding='utf8')
            print(name+': KILLED',flush=True)
        finally:
            file.write_bytes(original)
    print('All source bytes restored; run the unmodified full suite.',flush=True)

if __name__=='__main__': main()
