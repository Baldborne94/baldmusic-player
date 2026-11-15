<# : prompt dei comandi
@echo off
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Expression ([IO.File]::ReadAllText('%~f0', [Text.Encoding]::UTF8))"
if errorlevel 1 pause
goto :EOF
: da qui PowerShell #>
# BaldMusic - controllo dei brani
# Guarda ogni brano di una cartella e dice quali sono rovinati, tagliati, di qualita' bassa,
# anteprime o non musica. NON cambia, sposta o cancella niente: scrive solo un elenco sul Desktop.

function Find-Ffmpeg {
  $c = Get-Command ffmpeg.exe -ErrorAction SilentlyContinue
  if ($c) { return $c.Source }
  # Il percorso scritto nella configurazione di Navidrome, se c'e'.
  foreach ($toml in @("$env:ProgramData\Navidrome\navidrome.toml", "$env:ProgramFiles\Navidrome\navidrome.toml", "C:\Navidrome\navidrome.toml")) {
    if (Test-Path -LiteralPath $toml) {
      $m = Select-String -LiteralPath $toml -Pattern "FFmpegPath\s*=\s*['""]([^'""]+)['""]" | Select-Object -First 1
      if ($m) { $p = $m.Matches[0].Groups[1].Value; if (Test-Path -LiteralPath $p) { return $p } }
    }
  }
  $places = @(
    "$env:ProgramFiles\Navidrome\ffmpeg.exe",
    "$env:ProgramFiles\ffmpeg\bin\ffmpeg.exe",
    "C:\ffmpeg\bin\ffmpeg.exe",
    "$env:LOCALAPPDATA\Microsoft\WinGet\Links\ffmpeg.exe",
    "$env:ProgramData\chocolatey\bin\ffmpeg.exe",
    "$env:USERPROFILE\scoop\shims\ffmpeg.exe"
  )
  foreach ($p in $places) { if (Test-Path -LiteralPath $p) { return $p } }
  $pkg = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages"
  if (Test-Path -LiteralPath $pkg) {
    $f = Get-ChildItem -LiteralPath $pkg -Recurse -Filter ffmpeg.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($f) { return $f.FullName }
  }
  return $null
}

# Cosa c'e' davvero nel file, dai primi byte (non dal nome).
function Get-Kind([string]$path) {
  try {
    $fs = [IO.File]::OpenRead($path)
    $b = New-Object byte[] 16
    $n = $fs.Read($b, 0, 16)
    $fs.Close()
  } catch { return 'illeggibile' }
  if ($n -lt 4) { return 'vuoto' }
  $s = [Text.Encoding]::ASCII.GetString($b, 0, $n)
  if ($s.StartsWith('ID3')) { return 'id3' }
  if ($s.StartsWith('fLaC')) { return 'flac' }
  if ($s.StartsWith('OggS')) { return 'ogg' }
  if ($s.StartsWith('RIFF')) { return 'wav' }
  if ($s.StartsWith('FORM')) { return 'aiff' }
  if ($s.StartsWith('MAC ')) { return 'ape' }
  if ($s.StartsWith('wvpk')) { return 'wv' }
  if ($n -ge 8 -and $s.Substring(4, 4) -eq 'ftyp') { return 'mp4' }
  if ($b[0] -eq 0x1A -and $b[1] -eq 0x45 -and $b[2] -eq 0xDF -and $b[3] -eq 0xA3) { return 'webm' }
  if ($b[0] -eq 0x30 -and $b[1] -eq 0x26 -and $b[2] -eq 0xB2 -and $b[3] -eq 0x75) { return 'wma' }
  if ($b[0] -eq 0xFF -and ($b[1] -band 0xE0) -eq 0xE0) { if (($b[1] -band 0x06) -eq 0) { return 'aac' } else { return 'mp3' } }
  if ($s -match '^\s*<') { return 'pagina web' }
  return 'sconosciuto'
}

# I tipi giusti per ogni estensione ('id3' = tag all'inizio, normale per mp3, a volte flac/aac).
$expected = @{
  '.mp3' = @('mp3', 'id3'); '.flac' = @('flac', 'id3'); '.m4a' = @('mp4'); '.mp4' = @('mp4'); '.alac' = @('mp4')
  '.aac' = @('aac', 'mp4', 'id3'); '.ogg' = @('ogg'); '.opus' = @('ogg'); '.wav' = @('wav'); '.wma' = @('wma')
  '.aif' = @('aiff'); '.aiff' = @('aiff'); '.ape' = @('ape', 'id3'); '.wv' = @('wv')
}

# Un problema trovato: gruppo (1 = da riscaricare, 2 = qualita' bassa, 3 = da controllare) e spiegazione.
function Problem([int]$group, [string]$why) { [pscustomobject]@{ Group = $group; Why = $why } }

function Kind-Problems($file, [string]$kind) {
  $ok = $expected[$file.Extension.ToLower()]
  if ($kind -eq 'vuoto' -or $file.Length -lt 4096) { return @(Problem 1 'File vuoto o quasi: il download non e'' finito') }
  if ($kind -eq 'illeggibile') { return @(Problem 1 'Windows non riesce ad aprirlo') }
  if ($kind -eq 'pagina web') { return @(Problem 1 'Non e'' musica: e'' una pagina web salvata col nome del brano (download fallito)') }
  if ($ok -and $ok -notcontains $kind) {
    if ($kind -eq 'sconosciuto') { return @(Problem 1 'Non sembra un file audio') }
    return @(Problem 3 "In realta' e' un file $kind con l'estensione sbagliata: alcuni lettori non lo leggono")
  }
  return @()
}

function Seconds($m) { [int]$m.Groups[1].Value * 3600 + [int]$m.Groups[2].Value * 60 + [double]::Parse($m.Groups[3].Value, [Globalization.CultureInfo]::InvariantCulture) }
function Clock([double]$s) { '{0}:{1:00}' -f [Math]::Floor($s / 60), [Math]::Floor($s % 60) }

# Le regole sull'uscita di ffmpeg (provate su file veri: rovinati, tagliati, pagine web, qualita' bassa).
function Read-Check($file, [string]$text) {
  $out = @()
  $lines = $text -split "`r?`n"
  $errors = @($lines | Where-Object { $_ -match '\[error\]' })
  if ($lines | Where-Object { $_ -match '\[fatal\]' }) {
    $msg = if ($errors.Count) { ($errors[0] -split '\] ')[-1] } else { 'ffmpeg non riesce ad aprirlo' }
    return @(Problem 1 "Non si apre: $msg")
  }
  $d = [regex]::Match($text, 'Duration: (\d+):(\d\d):(\d\d(?:\.\d+)?)')
  $duration = if ($d.Success) { Seconds $d } else { $null }
  $t = [regex]::Matches($text, 'time=(\d+):(\d\d):(\d\d(?:\.\d+)?)')
  $played = if ($t.Count) { Seconds $t[$t.Count - 1] } else { $null }
  $a = [regex]::Match($text, 'Stream #0:\d+[^\r\n]*?: Audio: (\w+)[^\r\n]*?, (\d+) Hz[^\r\n]*')
  $codec = if ($a.Success) { $a.Groups[1].Value } else { '?' }
  $hz = if ($a.Success) { [int]$a.Groups[2].Value } else { $null }
  $kb = if ($a.Success) { [regex]::Match($a.Value, '(\d+) kb/s') } else { $null }
  if (-not $kb -or -not $kb.Success) { $kb = [regex]::Match($text, 'Duration:[^\r\n]*bitrate: (\d+) kb/s') }
  $kbps = if ($kb.Success) { [int]$kb.Groups[1].Value } else { $null }
  # Un mp3 senza indice ha la durata stimata: non si puo' dire se e' tagliato.
  $estimated = $text -match 'Estimating duration from bitrate'
  $cut = $duration -and $played -and -not $estimated -and ($played + [Math]::Max(5, $duration * 0.03)) -lt $duration
  if ($cut) { $out += Problem 1 ("Tagliato: si ferma a {0} su {1}" -f (Clock $played), (Clock $duration)) }
  if ($errors.Count -ge 10) { $out += Problem 1 "Rovinato: $($errors.Count) errori di lettura (salti, scricchiolii o silenzi)" }
  elseif ($errors.Count -gt 0) { $out += Problem 3 "Piccoli difetti: $($errors.Count) errori di lettura (forse un clic)" }
  $lossless = @('flac', 'alac', 'wavpack', 'ape', 'tta') -contains $codec -or $codec.StartsWith('pcm_')
  $limit = if ($codec -eq 'mp3') { 128 } else { 96 }
  if (-not $lossless -and $kbps -and $kbps -lt $limit) { $out += Problem 2 "Qualita' bassa: $kbps kbps ($codec), si sente impastato" }
  if ($hz -and $hz -lt 32000) { $out += Problem 2 "Qualita' bassa: campionato a $hz Hz, mancano gli acuti" }
  $long = if ($duration) { $duration } else { $played }
  if (-not $cut -and $long) {
    if ($long -ge 25 -and $long -le 35) { $out += Problem 3 ("Forse un'anteprima: dura {0}" -f (Clock $long)) }
    elseif ($long -lt 25) { $out += Problem 3 ("Molto corto: dura {0}" -f (Clock $long)) }
  }
  return $out
}

# Senza ffmpeg: solo quello che sa Windows (durata, bitrate). Non scopre i file rovinati dentro.
$shell = $null
$folders = @{}
function Quick-Check($file) {
  if (-not $script:shell) { $script:shell = New-Object -ComObject Shell.Application }
  $ns = $folders[$file.DirectoryName]
  if (-not $ns) { $ns = $script:shell.Namespace($file.DirectoryName); $folders[$file.DirectoryName] = $ns }
  $item = $ns.ParseName($file.Name)
  if (-not $item) { return @(Problem 1 'Windows non riesce ad aprirlo') }
  $out = @()
  $d = $item.ExtendedProperty('System.Media.Duration')
  $br = $item.ExtendedProperty('System.Audio.EncodingBitrate')
  $sr = $item.ExtendedProperty('System.Audio.SampleRate')
  $known = @('.mp3', '.m4a', '.flac', '.wma', '.wav') -contains $file.Extension.ToLower()
  if ($known -and -not $d) { return @(Problem 1 'Windows non riesce a leggerne la durata: forse e'' rovinato') }
  if ($d) {
    $s = [double]$d / 10000000
    if ($s -ge 25 -and $s -le 35) { $out += Problem 3 ("Forse un'anteprima: dura {0}" -f (Clock $s)) }
    elseif ($s -lt 25) { $out += Problem 3 ("Molto corto: dura {0}" -f (Clock $s)) }
  }
  $lossy = @('.mp3', '.m4a', '.aac', '.ogg', '.opus', '.wma') -contains $file.Extension.ToLower()
  $limit = if ($file.Extension.ToLower() -eq '.mp3') { 128 } else { 96 }
  if ($lossy -and $br -and ([int]$br / 1000) -lt $limit) { $out += Problem 2 ("Qualita' bassa: {0} kbps" -f [int]([int]$br / 1000)) }
  if ($sr -and [int]$sr -lt 32000) { $out += Problem 2 "Qualita' bassa: campionato a $sr Hz, mancano gli acuti" }
  return $out
}

function Main {
  [Console]::OutputEncoding = [Text.Encoding]::UTF8
  Write-Host ''
  Write-Host 'BaldMusic - controllo dei brani' -ForegroundColor Cyan
  Write-Host 'Non cambia, sposta o cancella niente: alla fine scrive un elenco sul Desktop.'
  Write-Host ''
  $default = 'E:\BalbMusic'
  $dir = Read-Host "Cartella da controllare (Invio = $default)"
  if (-not $dir) { $dir = $default }
  $dir = $dir.Trim().Trim('"')
  if (-not (Test-Path -LiteralPath $dir -PathType Container)) { Write-Host "La cartella $dir non c'e'." -ForegroundColor Red; return }
  $dir = (Resolve-Path -LiteralPath $dir).ProviderPath.TrimEnd('\')

  $ffmpeg = Find-Ffmpeg
  if (-not $ffmpeg) {
    Write-Host ''
    Write-Host 'Per sapere se un brano e'' rovinato dentro (salti, scricchiolii, tagliato) serve ffmpeg, gratuito.'
    if (Get-Command winget -ErrorAction SilentlyContinue) {
      $yes = Read-Host 'Lo installo adesso? (S/n)'
      if ($yes -eq '' -or $yes -match '^[sSyY]') {
        winget install --id Gyan.FFmpeg -e --accept-source-agreements --accept-package-agreements
        $ffmpeg = Find-Ffmpeg
      }
    }
    if (-not $ffmpeg) { Write-Host 'Senza ffmpeg faccio un controllo veloce: durata, qualita'', file che non sono musica.' -ForegroundColor Yellow }
  }

  $ext = @('.mp3', '.flac', '.m4a', '.aac', '.ogg', '.opus', '.wav', '.wma', '.alac', '.mp4', '.aif', '.aiff', '.ape', '.wv')
  Write-Host ''
  Write-Host "Cerco i brani in $dir ..."
  $files = @(Get-ChildItem -LiteralPath $dir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $ext -contains $_.Extension.ToLower() })
  $total = $files.Count
  if ($total -eq 0) { Write-Host 'Nessun brano trovato.' -ForegroundColor Yellow; return }
  Write-Host "Trovati $total brani. Controllo in corso (con ffmpeg ci vuole un po': ogni brano viene ascoltato fino in fondo, molto piu' veloce del normale)."

  $started = Get-Date
  $found = New-Object System.Collections.ArrayList
  $add = { param($file, $problems) foreach ($p in $problems) { [void]$found.Add([pscustomobject]@{ Group = $p.Group; Why = $p.Why; Path = $file.FullName.Substring($dir.Length + 1) }) } }
  $queue = New-Object System.Collections.Queue
  foreach ($f in $files) { $queue.Enqueue($f) }
  $running = New-Object System.Collections.ArrayList
  $max = [Math]::Max(1, [Environment]::ProcessorCount - 1)
  $done = 0
  $shown = Get-Date
  while ($queue.Count -gt 0 -or $running.Count -gt 0) {
    while ($running.Count -lt $max -and $queue.Count -gt 0) {
      $f = $queue.Dequeue()
      $kindProblems = @(Kind-Problems $f (Get-Kind $f.FullName))
      if ($kindProblems.Count -and $kindProblems[0].Group -eq 1) { & $add $f $kindProblems; $done++; continue }
      & $add $f $kindProblems
      if (-not $ffmpeg) {
        try { & $add $f (Quick-Check $f) } catch { & $add $f @(Problem 3 'Windows non riesce a leggerne i dati') }
        $done++
        continue
      }
      $psi = New-Object Diagnostics.ProcessStartInfo
      $psi.FileName = $ffmpeg
      $psi.Arguments = '-hide_banner -nostdin -nostats -loglevel level+info -i "' + $f.FullName + '" -map 0:a:0 -f null -'
      $psi.UseShellExecute = $false
      $psi.CreateNoWindow = $true
      $psi.RedirectStandardError = $true
      $psi.RedirectStandardOutput = $true
      $psi.StandardErrorEncoding = [Text.Encoding]::UTF8
      try {
        $p = [Diagnostics.Process]::Start($psi)
        [void]$running.Add([pscustomobject]@{ File = $f; Proc = $p; Err = $p.StandardError.ReadToEndAsync(); Out = $p.StandardOutput.ReadToEndAsync(); At = Get-Date })
      } catch {
        & $add $f @(Problem 3 "Non sono riuscito a controllarlo: $($_.Exception.Message)")
        $done++
      }
    }
    foreach ($r in @($running)) {
      if ($r.Proc.HasExited) {
        $r.Proc.WaitForExit()
        & $add $r.File (Read-Check $r.File $r.Err.Result)
        [void]$running.Remove($r)
        $done++
      } elseif (((Get-Date) - $r.At).TotalMinutes -gt 10) {
        try { $r.Proc.Kill() } catch {}
        & $add $r.File @(Problem 3 'Il controllo si e'' bloccato su questo brano: forse e'' rovinato')
        [void]$running.Remove($r)
        $done++
      }
    }
    if (((Get-Date) - $shown).TotalMilliseconds -gt 500) {
      $shown = Get-Date
      Write-Progress -Activity 'Controllo dei brani' -Status "$done di $total" -PercentComplete ([Math]::Min(100, $done * 100 / $total))
    }
    if ($running.Count -gt 0) { Start-Sleep -Milliseconds 40 }
  }
  Write-Progress -Activity 'Controllo dei brani' -Completed

  $took = (Get-Date) - $started
  $titles = @{
    1 = 'DA RISCARICARE (rovinati, tagliati, vuoti o che non sono musica)'
    2 = "QUALITA' BASSA (si sentono peggio: meglio riscaricarli in qualita' migliore)"
    3 = 'DA CONTROLLARE (anteprime, brani molto corti, piccoli difetti, estensione sbagliata)'
  }
  $report = New-Object System.Collections.ArrayList
  [void]$report.Add('BaldMusic - controllo dei brani')
  [void]$report.Add("Cartella: $dir")
  [void]$report.Add(("Brani controllati: {0} in {1:0} minuti, {2}" -f $total, $took.TotalMinutes, (Get-Date -Format 'dd/MM/yyyy HH:mm')))
  if ($ffmpeg) { [void]$report.Add('Controllo completo con ffmpeg: ogni brano ascoltato fino in fondo.') }
  else { [void]$report.Add('Controllo veloce senza ffmpeg: i file rovinati dentro non si vedono.') }
  [void]$report.Add('Nessun file e'' stato toccato.')
  foreach ($g in 1, 2, 3) {
    $items = @($found | Where-Object { $_.Group -eq $g } | Sort-Object Path)
    $files_in = @($items | Select-Object -ExpandProperty Path -Unique).Count
    [void]$report.Add('')
    [void]$report.Add("== $($titles[$g]): $files_in brani ==")
    foreach ($i in $items) { [void]$report.Add("$($i.Path)  ->  $($i.Why)") }
  }
  $bad = @($found | Select-Object -ExpandProperty Path -Unique).Count
  [void]$report.Add('')
  [void]$report.Add("Tutto a posto per $($total - $bad) brani su $total.")
  $out = Join-Path ([Environment]::GetFolderPath('Desktop')) 'baldmusic-controllo.txt'
  [IO.File]::WriteAllLines($out, [string[]]$report, (New-Object Text.UTF8Encoding $true))
  Write-Host ''
  Write-Host "Fatto: $bad brani da guardare su $total. L'elenco e' in $out" -ForegroundColor Green
  Start-Process notepad.exe -ArgumentList ('"' + $out + '"')
}

Main
Read-Host "Premi Invio per chiudere"
