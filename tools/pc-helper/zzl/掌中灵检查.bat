@echo off
title ZZL Guard Check
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0zzl_guard.ps1"

pause
