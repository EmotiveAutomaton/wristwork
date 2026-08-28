' Runs a script with NO console window — Task Scheduler's -WindowStyle Hidden still flashes a
' window; this wrapper does not. Usage: wscript run_hidden.vbs <path-to-ps1-or-py>
Set sh = CreateObject("Wscript.Shell")
target = WScript.Arguments(0)
If LCase(Right(target, 3)) = ".py" Then
    sh.Run "python """ & target & """", 0, False
Else
    sh.Run "powershell -NoProfile -ExecutionPolicy Bypass -File """ & target & """", 0, False
End If
