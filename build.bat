@echo off
setlocal
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
(for /r src\main\java %%f in (*.java) do @echo "%%f") > build\sources.txt
javac --release 21 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
jar --create --file build\almoxarifado.jar --main-class br.com.almoxarifado.Main -C build\classes .
if errorlevel 1 exit /b 1
