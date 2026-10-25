@echo off
chcp 65001 >nul
cd /d "%~dp0"
for %%f in (*.py) do start "" "C:\Users\Administrator\AppData\Local\Programs\Python\Python311\pythonw.exe" "%%~ff"
