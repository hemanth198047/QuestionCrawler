@echo off
REM Double-click to build and start QuestionCrawler (Windows).
cd /d "%~dp0"
node scripts\start.mjs %*
pause
