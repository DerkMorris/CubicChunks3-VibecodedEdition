@echo off
setlocal
if not defined JAVA_HOME if exist "%APPDATA%\.minecraft\runtime\java-runtime-epsilon\windows\java-runtime-epsilon\bin\javac.exe" set "JAVA_HOME=%APPDATA%\.minecraft\runtime\java-runtime-epsilon\windows\java-runtime-epsilon"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
pushd "%~dp0"
if "%~1"=="" (
  call gradlew.bat build
) else (
  call gradlew.bat %*
)
set "BUILD_RESULT=%ERRORLEVEL%"
popd
exit /b %BUILD_RESULT%
