$ErrorActionPreference = 'Stop'

Write-Host 'Checking Windows build prerequisites...'
foreach ($tool in @('node', 'npm', 'rustc', 'cargo')) {
  if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
    throw "Missing '$tool'. Install Node.js 20+, Rust (rustup), and the MSVC Rust toolchain, then reopen PowerShell."
  }
}

$rustup = Get-Command rustup -ErrorAction SilentlyContinue
if (-not $rustup) {
  throw 'Missing rustup. Install Rust from https://rustup.rs and select the MSVC toolchain.'
}

$target = (& rustup show active-toolchain 2>$null)
if ($target -notmatch 'x86_64-pc-windows-msvc') {
  Write-Warning "Active Rust toolchain is '$target'. Tauri Windows builds require x86_64-pc-windows-msvc."
}

if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
  Write-Warning 'cl.exe was not found in PATH. Run this from a Visual Studio Developer PowerShell with Desktop C++ workload installed.'
}

Set-Location (Join-Path $PSScriptRoot '..')
npm ci
npm run tauri build

Write-Host ''
Write-Host 'Windows installers:'
Get-ChildItem 'src-tauri\target\release\bundle\nsis\*.exe', 'src-tauri\target\release\bundle\msi\*.msi'
