@echo off
setlocal
cd /d "%~dp0.."
if not exist "tests\out" mkdir "tests\out"
javac -encoding UTF-8 -d tests\out app\src\main\java\com\compwave\tach\VibrationSample.java app\src\main\java\com\compwave\tach\RpmAnalyzer.java tests\AnalyzerSelfTest.java
if errorlevel 1 exit /b 1
java -Dfile.encoding=UTF-8 -cp tests\out AnalyzerSelfTest
exit /b %ERRORLEVEL%
