@echo off
cd /d "%~dp0"
call build.bat
if errorlevel 1 exit /b 1
if not exist build\test-classes mkdir build\test-classes
(for /r src\test\java %%f in (*.java) do @echo "%%f") > build\test-sources.txt
javac --release 21 -encoding UTF-8 -cp build\classes -d build\test-classes @build\test-sources.txt
if errorlevel 1 exit /b 1
java -cp "build\classes;build\test-classes" br.com.almoxarifado.SystemTest
