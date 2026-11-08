$ErrorActionPreference = 'Stop'
$res = Join-Path $PSScriptRoot 'app\src\main\res'
$gbk = [Text.Encoding]::GetEncoding(936)
$utf8 = [Text.UTF8Encoding]::new($false, $true)
function Repair([string] $Text) {
    try { return $utf8.GetString($gbk.GetBytes($Text)) } catch { return $Text }
}
Get-ChildItem $res -Directory -Filter 'values*' | ForEach-Object {
    $file = Join-Path $_.FullName 'strings.xml'
    [xml]$xml = Get-Content -Raw -Encoding UTF8 $file
    $strings = @($xml.resources.string)
    $broken = @($strings | Where-Object { $_.'#text' -match '姝|鐧|銉|鞚|鎴|璇' })
    Write-Output "$($_.Name): $($strings.Count) keys, $($broken.Count) suspect values"
    if ($_.Name -eq 'values-en') {
        $broken | ForEach-Object { Write-Output ("{0} = {1}" -f $_.name, (Repair $_.'#text')) }
    }
}
