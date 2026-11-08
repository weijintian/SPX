import subprocess
adb=r'C:\Users\雷神\AppData\Local\Android\Sdk\platform-tools\adb.exe'
def run(args):
 return subprocess.check_output([adb]+args, text=True, errors='ignore')
print(run(['shell','dumpsys','activity','activities'])[-4000:])
print('---CRASH---')
print(run(['logcat','-d','-b','crash','-t','200'])[-8000:])
