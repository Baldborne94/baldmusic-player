@echo off
chcp 65001 >nul
rem Android Auto sul PC: avvia il Desktop Head Unit collegato al telefono col cavo USB.
rem Guida e prove da fare: COLLAUDO.md, «Android Auto sul PC».
setlocal
set "SDK=%ANDROID_HOME%"
if "%SDK%"=="" set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "DHU=%SDK%\extras\google\auto\desktop-head-unit.exe"

if not exist "%ADB%" (
  echo Non trovo adb in "%SDK%\platform-tools".
  echo Installa gli strumenti come spiega COLLAUDO.md, oppure imposta ANDROID_HOME sulla cartella dell'SDK.
  pause
  exit /b 1
)
if not exist "%DHU%" (
  echo Non trovo il Desktop Head Unit in "%SDK%\extras\google\auto".
  echo Installalo con: sdkmanager "extras;google;auto"
  pause
  exit /b 1
)

"%ADB%" forward tcp:5277 tcp:5277
if errorlevel 1 (
  echo Il telefono non risponde: collegalo col cavo, sbloccalo e accetta «Consenti debug USB».
  pause
  exit /b 1
)

echo Sul telefono: Android Auto, menu ⋮, «Avvia server unità principale» deve essere attivo.
echo Nella finestra nera del Desktop Head Unit: M = parla (come il tasto sul volante), N = giorno/notte.
cd /d "%SDK%\extras\google\auto"
"%DHU%" %*
