# Starts a command in the desktop session (scheduled task with /IT), hidden. Usage: gui.ps1 <name> <cmd> [args]
param($name, [Parameter(ValueFromRemainingArguments)]$rest)
schtasks /Create /F /TN $name /TR "wscript.exe C:\Users\USER\shortstop-test\hide.vbs $($rest -join ' ')" /SC ONCE /ST 23:59 /IT /RL LIMITED | Out-Null
schtasks /Run /TN $name | Out-Null
"ran $name"
