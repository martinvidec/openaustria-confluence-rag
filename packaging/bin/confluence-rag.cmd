@echo off
rem Confluence RAG — CLI wrapper (Windows)
rem Delegates to confluence-rag.ps1 in the same directory.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0confluence-rag.ps1" %*
