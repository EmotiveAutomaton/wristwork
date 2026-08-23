' Runs a PowerShell script with NO console window — Task Scheduler's -WindowStyle Hidden still
' flashes a window; this wrapper does not. Usage: wscript run_hidden.vbs <path-to-ps1>
Set sh = CreateObject("Wscript.Shell")
sh.Run "powershell -NoProfile -ExecutionPolicy Bypass -File """ & WScript.Arguments(0) & """", 0, False
