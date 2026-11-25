@echo off
if not exist out mkdir out
dir /s /b *.java > sources.txt
javac -d out @sources.txt
if %errorlevel% neq 0 (echo Build failed & exit /b 1)
del sources.txt
echo Build done
