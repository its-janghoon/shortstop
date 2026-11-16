' Runs a command from the work folder with no console window.
Dim a, i: a = ""
For i = 0 To WScript.Arguments.Count - 1: a = a & " " & WScript.Arguments(i): Next
CreateObject("WScript.Shell").Run "cmd /c C:\Users\USER\shortstop-test\" & Trim(a), 0, False
