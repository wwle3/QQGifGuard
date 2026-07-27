@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "ROOT_DIR=%~dp0.."
for %%I in ("%ROOT_DIR%") do set "ROOT_DIR=%%~fI"
set "MODULE_DIR=%ROOT_DIR%\module"

set "VARIANT=%~1"
if "%VARIANT%"=="" set "VARIANT=debug"

if /I "%VARIANT%"=="debug" (
  set "TASK=:app:assembleDebug"
  set "APK_REL=app\build\outputs\apk\debug\app-debug.apk"
) else if /I "%VARIANT%"=="release" (
  set "TASK=:app:assembleRelease"
  set "APK_REL=app\build\outputs\apk\release\app-release-unsigned.apk"
) else (
  echo Usage: %~nx0 [debug^|release]
  exit /b 2
)

if not defined ANDROID_HOME if not defined ANDROID_SDK_ROOT (
  echo ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must be set.
  exit /b 1
)
if defined ANDROID_HOME (
  set "SDK_DIR=%ANDROID_HOME%"
) else (
  set "SDK_DIR=%ANDROID_SDK_ROOT%"
)

if not exist "%MODULE_DIR%\local.properties" (
  > "%MODULE_DIR%\local.properties" echo sdk.dir=%SDK_DIR:\=/%
  echo Wrote %MODULE_DIR%\local.properties
)

echo Building %VARIANT% with JAVA_HOME=%JAVA_HOME%
echo Android SDK: %SDK_DIR%

pushd "%MODULE_DIR%" >nul
call gradlew.bat --no-daemon %TASK%
set "EC=%ERRORLEVEL%"
popd >nul
if not "%EC%"=="0" exit /b %EC%

set "APK_PATH=%MODULE_DIR%\%APK_REL%"
if not exist "%APK_PATH%" (
  echo ERROR: expected APK not found: %APK_PATH%
  exit /b 1
)

if not exist "%ROOT_DIR%\dist" mkdir "%ROOT_DIR%\dist"
set "OUT_APK=%ROOT_DIR%\dist\QQGifGuard-%VARIANT%.apk"
copy /Y "%APK_PATH%" "%OUT_APK%" >nul

echo.
echo BUILD SUCCESS
echo APK: %OUT_APK%
exit /b 0
